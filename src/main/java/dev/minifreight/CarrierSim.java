package dev.minifreight;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.minimart.core.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * THE OUTSIDE WORLD, WITH ITS OWN OPINIONS.
 *
 * Two carriers, reachable only over HTTP, exactly as minipay is to minimart.
 * Freight holds no reference to this class and this class holds none to
 * freight: if either one cheated, every lesson downstream would be proving
 * something about a function call instead of something about an integration.
 *
 * The personalities are the point, and both are copied from carriers that
 * exist:
 *
 *   SWIFT  · reliable, and picky. Answers what it means, delivers every
 *            webhook exactly once, refuses anything oversize.
 *   BUDGET · takes what swift will not, and is a catalogue of the ways an
 *            external system actually misbehaves. Its label endpoint WRITES
 *            THE LABEL AND THEN RETURNS 500, so the caller's "unknown" is a
 *            label that exists. It delivers every webhook twice. And it can go
 *            silent entirely, so the only way to learn a parcel moved is to
 *            ask.
 *
 * That first budget behaviour is the one that earns its keep: it makes "retry
 * the POST somewhere else" the wrong move in a way a test can catch, which is
 * the whole argument for the freight driver's adopt-before-abandon rule.
 *
 * Webhooks are signed · HMAC-SHA256 over the exact body, hex, in
 * X-Carrier-Signature. A tracking status is a business event with money
 * downstream of it, and an unsigned business event is an invitation.
 */
public final class CarrierSim {

    public static final String SWIFT = "swift";
    public static final String BUDGET = "budget";

    /** Shared with the freight side by configuration, as a real integration
     *  would share them by onboarding. Constants here because the sim IS the
     *  other party's infrastructure. */
    public static final Map<String, String> SECRETS = Map.of(
            SWIFT, "swift-hook-secret-01",
            BUDGET, "budget-hook-secret-01");

    /** swift refuses above this; budget above its own, higher, bar. A parcel
     *  too big for both is how the "every carrier said no" path is exercised
     *  honestly, with two real refusals rather than a mock. */
    public static final long SWIFT_MAX_QTY = 10, BUDGET_MAX_QTY = 50;

    private record Label(String requestId, String tracking, long qty) {}

    private final HttpServer server;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private final String webhookBase;
    private final AtomicLong seq = new AtomicLong();

    // per carrier: requestId -> label, tracking -> parcel status
    private final Map<String, Map<String, Label>> labels = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> parcels = new ConcurrentHashMap<>();

    /** Carriers listed here record movement but tell nobody. A test seam,
     *  because "the webhook never came" is a case the driver must survive. */
    public final java.util.Set<String> silent = ConcurrentHashMap.newKeySet();

    /** Every 500 budget returned after writing the label, counted, so a test
     *  can assert the ugly path actually ran rather than assuming it did. */
    public final AtomicLong budgetWroteThenFailed = new AtomicLong();

    private CarrierSim(HttpServer server, String webhookBase) {
        this.server = server;
        this.webhookBase = webhookBase;
        for (String carrier : new String[]{SWIFT, BUDGET}) {
            labels.put(carrier, new ConcurrentHashMap<>());
            parcels.put(carrier, new ConcurrentHashMap<>());
        }
    }

    public static CarrierSim start(int port, String webhookBase) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
        CarrierSim sim = new CarrierSim(s, webhookBase);
        s.createContext("/carriers/", sim::route);
        s.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        s.start();
        return sim;
    }

    public int port() { return server.getAddress().getPort(); }
    public void stop() { server.stop(0); }

    // ---------------------------------------------------------------- routing

    private void route(HttpExchange x) throws IOException {
        try {
            String[] p = x.getRequestURI().getPath().split("/");
            // /carriers/{name}/labels[/{requestId}] · /carriers/{name}/advance
            if (p.length < 4 || !labels.containsKey(p[2])) { respond(x, 404, "{\"error\":\"no such carrier\"}"); return; }
            String carrier = p[2];
            switch (p[3]) {
                case "labels" -> {
                    if ("POST".equals(x.getRequestMethod())) requestLabel(x, carrier);
                    else if (p.length == 5) labelStatus(x, carrier, p[4]);
                    else respond(x, 404, "{}");
                }
                case "advance" -> advance(x, carrier);
                default -> respond(x, 404, "{}");
            }
        } catch (Exception e) {
            respond(x, 500, "{\"error\":\"" + Json.esc(e.getMessage()) + "\"}");
        }
    }

    /**
     * POST /carriers/{name}/labels · idempotent by the CALLER'S request id.
     *
     * Both carriers honour a replayed request id by answering what they
     * already did, which is what any carrier API worth integrating does. The
     * difference is what happens the FIRST time: swift answers what happened,
     * budget sometimes does the thing and then fails to say so.
     */
    private void requestLabel(HttpExchange x, String carrier) throws IOException {
        String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String requestId = Json.str(body, "requestId");
        long qty = parseQty(Json.str(body, "qty"));
        if (requestId == null || requestId.isBlank()) { respond(x, 400, "{\"error\":\"requestId required\"}"); return; }

        long max = SWIFT.equals(carrier) ? SWIFT_MAX_QTY : BUDGET_MAX_QTY;
        if (labels.get(carrier).get(requestId) == null && qty > max) {
            respond(x, 200, Json.obj("status", "rejected", "reason", "oversize for " + carrier));
            return;
        }

        // putIfAbsent, not check-then-put: two deliveries of one request id
        // racing each other must converge on ONE label, because this map is
        // the idempotency a real carrier would be running, and an idempotency
        // layer with a window in it is a lottery. The loser's tracking number
        // is never spoken and never enters the fleet.
        String minted = (SWIFT.equals(carrier) ? "SW-" : "BG-") + seq.incrementAndGet();
        Label prior = labels.get(carrier).putIfAbsent(requestId, new Label(requestId, minted, qty));
        if (prior != null) {      // a replay is answered, never re-done
            respond(x, 200, Json.obj("status", "accepted", "tracking", prior.tracking()));
            return;
        }
        String tracking = minted;
        parcels.get(carrier).put(tracking, "labelled");

        if (BUDGET.equals(carrier)) {
            // The label is written. The caller is told nothing useful. This is
            // not sabotage for its own sake: it is the crash-after-commit every
            // remote system has, compressed into something a test can schedule.
            budgetWroteThenFailed.incrementAndGet();
            respond(x, 500, "{\"error\":\"internal error\"}");
            return;
        }
        respond(x, 200, Json.obj("status", "accepted", "tracking", tracking));
    }

    /** GET /carriers/{name}/labels/{requestId} · the fresh answer the freight
     *  driver is required to ask for before it acts on an unknown. */
    private void labelStatus(HttpExchange x, String carrier, String requestId) throws IOException {
        Label l = labels.get(carrier).get(requestId);
        if (l == null) { respond(x, 200, Json.obj("status", "not_found")); return; }
        respond(x, 200, Json.obj("status", "accepted", "tracking", l.tracking(),
                "parcel", parcels.get(carrier).getOrDefault(l.tracking(), "labelled")));
    }

    /**
     * POST /carriers/{name}/advance {"tracking": ...} · the world moves.
     *
     * Deterministic on purpose: a parcel advances when the test or the
     * simulation says so, not when a timer fires, so a compressed run can walk
     * a parcel across a continent inside one tick. Each advance notifies
     * freight the way that carrier notifies anybody · once, twice, or not at
     * all.
     */
    private void advance(HttpExchange x, String carrier) throws IOException {
        String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String tracking = Json.str(body, "tracking");
        Map<String, String> fleet = parcels.get(carrier);
        String current = tracking == null ? null : fleet.get(tracking);
        if (current == null) { respond(x, 404, "{\"error\":\"unknown parcel\"}"); return; }

        String next = switch (current) {
            case "labelled" -> "in_transit";
            case "in_transit" -> "delivered";
            default -> current;                     // delivered parcels stay delivered
        };
        fleet.put(tracking, next);

        if (!silent.contains(carrier)) {
            int deliveries = BUDGET.equals(carrier) ? 2 : 1;   // budget always stutters
            for (int i = 0; i < deliveries; i++) webhook(carrier, tracking, next);
        }
        respond(x, 200, Json.obj("tracking", tracking, "parcel", next));
    }

    /** The event key is the CARRIER'S, minted from its own names, which is why
     *  freight dedupes it per carrier rather than globally. */
    private void webhook(String carrier, String tracking, String status) {
        String body = Json.obj("tracking", tracking, "status", status,
                "eventKey", tracking + ":" + status, "at", Instant.now().toString());
        try {
            http.send(HttpRequest.newBuilder(URI.create(webhookBase + "/webhooks/carrier/" + carrier))
                    .timeout(Duration.ofSeconds(3))
                    .header("X-Carrier-Signature", hmac(SECRETS.get(carrier), body))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception ignored) {
            // a carrier does not retry for our convenience; the silent path and
            // the freight driver's polling exist precisely because of this
        }
    }

    static String hmac(String secret, String body) {
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(raw.length * 2);
            for (byte v : raw) b.append(String.format("%02x", v));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static long parseQty(String s) {
        try { return s == null ? 0 : Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private static void respond(HttpExchange x, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = x.getResponseBody()) { os.write(bytes); }
    }
}
