package dev.minimart.sim;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * THE SYNTHETIC CUSTOMER ENGINE.
 *
 * A seeded population of agent customers drives minimart through its real HTTP
 * API: the same endpoints, the same idempotency gates, the same constraints a
 * browser would meet. There is no privileged path for the simulation.
 *
 * Every decision is derived from (runId, agentId, tick, step), so a run is
 * reproducible even though agents execute concurrently: the decision log is
 * sorted before it is hashed. Time is compressed by the tick, and the platform
 * is told the business time explicitly, so a week of subscriptions and
 * abandoned carts can pass in seconds without the logic noticing.
 *
 * WHAT THIS LEGITIMATELY PROVES: that the platform holds up under a realistic
 * mix of concurrent behaviour, that its invariants survive, that retries and
 * expiries do the right thing. WHAT IT DOES NOT PROVE: real conversion rates.
 * These agents were authored; they are a load and logic oracle, not a forecast.
 */
public final class SimRunner {

    /** A customer, derived entirely from the seed. */
    public record Persona(int id, BigDecimal budget, double buyPropensity,
                          double shipPropensity, double priceSensitivity) {}

    public record Result(List<String> decisions, int placed, int rejected, int shipped, int released) {}

    private final String base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public SimRunner(String baseUrl) { this.base = baseUrl; }

    public static Persona persona(String runId, int agentId) {
        double b = Seeds.unit(runId, agentId, 0, "budget");
        return new Persona(agentId,
                BigDecimal.valueOf(40 + Math.round(b * 260)),          // 40 to 300
                0.15 + Seeds.unit(runId, agentId, 0, "buy") * 0.45,     // some shop often, some rarely
                0.55 + Seeds.unit(runId, agentId, 0, "ship") * 0.45,    // some abandon their cart
                0.6 + Seeds.unit(runId, agentId, 0, "price") * 0.6);
    }

    /**
     * @param tickDuration how much business time one tick represents
     */
    public Result run(String runId, int agents, int ticks, String tenant, String location,
                      Instant start, Duration tickDuration) throws Exception {
        List<Variant> catalog = catalog();
        if (catalog.isEmpty()) throw new IllegalStateException("catalog is empty");

        List<String> log = Collections.synchronizedList(new ArrayList<>());
        List<UUID> awaitingShip = Collections.synchronizedList(new ArrayList<>());
        int placed = 0, rejected = 0, shipped = 0, released = 0;

        for (int tick = 0; tick < ticks; tick++) {
            final int t = tick;
            final Instant now = start.plus(tickDuration.multipliedBy(tick));
            List<String> tickLog = Collections.synchronizedList(new ArrayList<>());

            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> fs = new ArrayList<>();
                for (int a = 0; a < agents; a++) {
                    final int agentId = a;
                    fs.add(pool.submit(() -> { step(runId, agentId, t, now, tenant, location, catalog,
                            tickLog, awaitingShip); return null; }));
                }
                for (Future<?> f : fs) f.get(120, TimeUnit.SECONDS);
            }
            log.addAll(tickLog);

            // the barrier: time-driven jobs run once, in a fixed place in the tick
            String tickResult = post("/api/sim/tick", "{\"business_at\":\"" + now + "\"}");
            released += intField(tickResult, "reservations_released");
        }

        for (String d : log) {
            if (d.contains("|placed")) placed++;
            else if (d.contains("|rejected")) rejected++;
            else if (d.contains("|shipped")) shipped++;
        }
        // sorted so a concurrent run still hashes identically
        List<String> ordered = new ArrayList<>(log);
        ordered.sort(Comparator.naturalOrder());
        return new Result(ordered, placed, rejected, shipped, released);
    }

    private void step(String runId, int agentId, int tick, Instant now, String tenant, String location,
                      List<Variant> catalog, List<String> log, List<UUID> awaitingShip) {
        Persona p = persona(runId, agentId);
        try {
            // does this customer shop at all this tick?
            if (Seeds.unit(runId, agentId, tick, "shop") < p.buyPropensity()) {
                Variant v = catalog.get(Seeds.intIn(runId, agentId, tick, "pick", catalog.size()));
                BigDecimal willingToPay = p.budget().multiply(BigDecimal.valueOf(p.priceSensitivity()));
                if (v.price().compareTo(willingToPay) <= 0) {
                    UUID orderId = Seeds.uuid(runId, agentId, tick, "order");
                    String body = "{\"orderId\":\"" + orderId + "\",\"tenant\":\"" + tenant +
                            "\",\"customer\":\"" + (1000 + agentId) + "\",\"variant\":\"" + v.id() +
                            "\",\"location\":\"" + location + "\",\"qty\":1,\"business_at\":\"" + now + "\"}";
                    String r = post("/api/checkout", body);
                    if (r.contains("\"state\":\"reserved\"")) {
                        log.add(key(tick, agentId) + "|placed|" + v.id());
                        awaitingShip.add(orderId);
                    } else {
                        log.add(key(tick, agentId) + "|rejected|" + v.id());
                    }
                } else {
                    log.add(key(tick, agentId) + "|too_expensive|" + v.id());
                }
            }
            // and do they complete a previous purchase, or abandon it to expire?
            UUID mine = Seeds.uuid(runId, agentId, Math.max(0, tick - 1), "order");
            if (awaitingShip.remove(mine) && Seeds.unit(runId, agentId, tick, "ship") < p.shipPropensity()) {
                post("/api/orders/" + mine + "/ship", "{\"business_at\":\"" + now + "\"}");
                log.add(key(tick, agentId) + "|shipped");
            }
        } catch (Exception e) {
            log.add(key(tick, agentId) + "|error|" + e.getClass().getSimpleName());
        }
    }

    private static String key(int tick, int agentId) {
        return String.format("t%05d|a%05d", tick, agentId);
    }

    public record Variant(String id, BigDecimal price) {}

    private List<Variant> catalog() throws Exception {
        String body = get("/api/catalog");
        List<Variant> out = new ArrayList<>();
        for (String chunk : body.split("\\{")) {
            String id = between(chunk, "\"id\":\"", "\"");
            String price = between(chunk, "\"price\":\"", "\"");
            if (id != null && price != null) out.add(new Variant(id, new BigDecimal(price)));
        }
        return out;
    }

    private static String between(String s, String a, String b) {
        int i = s.indexOf(a);
        if (i < 0) return null;
        int j = s.indexOf(b, i + a.length());
        return j < 0 ? null : s.substring(i + a.length(), j);
    }

    private static int intField(String json, String key) {
        String v = between(json, "\"" + key + "\":", "}");
        if (v == null) return 0;
        try { return Integer.parseInt(v.trim().split(",")[0].trim()); } catch (Exception e) { return 0; }
    }

    private String post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private String get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }
}
