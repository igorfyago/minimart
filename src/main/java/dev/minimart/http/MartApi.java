package dev.minimart.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.minimart.commerce.Billing;
import dev.minimart.commerce.Checkout;
import dev.minimart.commerce.Orders;
import dev.minimart.commerce.ReservationSweeper;
import dev.minimart.core.Db;
import dev.minimart.core.Json;
import dev.minimart.core.Ledger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * minimart's public surface. Everything an agent customer will ever touch goes
 * through here, over HTTP, exactly as a browser would: the same idempotency
 * gates, the same constraints, no privileged back door for the simulation.
 *
 * /api/sim/tick is the one concession to simulation: it runs the time-driven
 * jobs (currently the reservation sweeper) as a single deterministic pass at a
 * tick boundary, instead of a background loop. That is what makes a compressed
 * run reproducible.
 */
public final class MartApi {

    private MartApi() {}

    /**
     * WHO IS CALLING. ANONYMOUS today — the estate rollout is permissive:
     * tokens are recognized when present (this seam is where they'll be
     * read) and nothing is ever rejected for lacking one. When sso-client
     * becomes a resolvable artifact here, the adapter in CallerIdentity's
     * javadoc replaces ANONYMOUS with the real check, and every endpoint
     * below starts serving the token's customer over the request's claim
     * without changing a line of their own.
     */
    static volatile CallerIdentity identity = CallerIdentity.ANONYMOUS;

    public static HttpServer start(int port) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.createContext("/api/catalog", MartApi::catalog);
        s.createContext("/api/checkout", MartApi::checkout);
        s.createContext("/api/orders", MartApi::orders);
        s.createContext("/api/stock", MartApi::stock);
        s.createContext("/api/sim/tick", MartApi::tick);
        s.createContext("/api/sim/run", MartApi::simRun);
        s.createContext("/api/sim/status", MartApi::simStatus);
        s.createContext("/api/audit", MartApi::audit);
        s.createContext("/api/stats", MartApi::stats);
        s.createContext("/api/orders/recent", MartApi::recentOrders);
        s.createContext("/api/stock/levels", MartApi::stockLevels);
        s.createContext("/api/subscriptions", MartApi::subscriptions);
        s.createContext("/api/subscribe", MartApi::subscribe);
        s.createContext("/api/unsubscribe", MartApi::unsubscribe);
        s.createContext("/", MartApi::staticFile);
        s.start();
        return s;
    }

    // ------------------------------------------------------------ the shop UI

    private static void staticFile(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
        String type = path.endsWith(".css") ? "text/css" : path.endsWith(".js") ? "application/javascript"
                : path.endsWith(".svg") ? "image/svg+xml" : "text/html; charset=utf-8";
        try (var in = MartApi.class.getResourceAsStream("/web" + path)) {
            if (in == null) { send(ex, 404, "{\"error\":\"not found\"}"); return; }
            byte[] b = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", type);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        }
    }

    /** Headline numbers for the operations view. */
    private static void stats(HttpExchange ex) throws IOException {
        try (Connection c = Db.open()) {
            long orders = one(c, "SELECT COUNT(*) FROM orders");
            long shipped = one(c, "SELECT COUNT(*) FROM orders WHERE state = 'fulfilled'");
            long reserved = one(c, "SELECT COUNT(*) FROM orders WHERE state = 'reserved'");
            long aborted = one(c, "SELECT COUNT(*) FROM orders WHERE state = 'aborted'");
            long subs = one(c, "SELECT COUNT(*) FROM subscriptions WHERE status = 'active'");
            long invoices = one(c, "SELECT COUNT(*) FROM invoices WHERE status = 'paid'");
            String revenue;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COALESCE(SUM(amount),0) FROM orders WHERE state = 'fulfilled'");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                revenue = rs.getBigDecimal(1).stripTrailingZeros().toPlainString();
            }
            send(ex, 200, "{\"orders\":" + orders + ",\"shipped\":" + shipped + ",\"reserved\":" + reserved +
                    ",\"aborted\":" + aborted + ",\"subscriptions\":" + subs + ",\"invoices\":" + invoices +
                    ",\"revenue\":\"" + revenue + "\"}");
        } catch (Exception e) { send(ex, 500, err(e)); }
    }

    private static void recentOrders(HttpExchange ex) throws IOException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT o.id, o.customer_id, o.variant_id, o.qty, o.amount, o.state, o.payment_mode,
                            o.business_at, v.title
                     FROM orders o JOIN variants v ON v.id = o.variant_id
                     ORDER BY o.business_at DESC, o.id LIMIT 25""");
             ResultSet rs = ps.executeQuery()) {
            StringBuilder b = new StringBuilder("[");
            boolean first = true;
            while (rs.next()) {
                if (!first) b.append(',');
                first = false;
                b.append("{\"id\":\"").append(rs.getString(1))
                 .append("\",\"customer\":").append(rs.getLong(2))
                 .append(",\"variant\":\"").append(Json.esc(rs.getString(3)))
                 .append("\",\"qty\":").append(rs.getLong(4))
                 .append(",\"amount\":\"").append(rs.getBigDecimal(5).stripTrailingZeros().toPlainString())
                 .append("\",\"state\":\"").append(rs.getString(6))
                 .append("\",\"mode\":\"").append(rs.getString(7))
                 .append("\",\"at\":\"").append(rs.getTimestamp(8).toInstant())
                 .append("\",\"title\":\"").append(Json.esc(rs.getString(9))).append("\"}");
            }
            send(ex, 200, b.append(']').toString());
        } catch (Exception e) { send(ex, 500, err(e)); }
    }

    private static void stockLevels(HttpExchange ex) throws IOException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("SELECT id, title, price FROM variants ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            StringBuilder b = new StringBuilder("[");
            boolean first = true;
            while (rs.next()) {
                String v = rs.getString(1);
                if (!first) b.append(',');
                first = false;
                b.append("{\"id\":\"").append(Json.esc(v))
                 .append("\",\"title\":\"").append(Json.esc(rs.getString(2)))
                 .append("\",\"price\":\"").append(rs.getBigDecimal(3).stripTrailingZeros().toPlainString())
                 .append("\",\"onHand\":").append(bal(c, Orders.onHand("MAD", v)))
                 .append(",\"reserved\":").append(bal(c, Orders.reserved("MAD", v)))
                 .append(",\"sold\":").append(bal(c, Orders.sold("MAD", v))).append('}');
            }
            send(ex, 200, b.append(']').toString());
        } catch (Exception e) { send(ex, 500, err(e)); }
    }


    /**
     * Subscribing is a PUBLIC action, so the agent customers can do it through
     * the same door a browser uses. There is no back channel into Billing for
     * the simulation, which is the whole reason the simulation is worth
     * anything: it exercises the endpoint that real traffic would hit.
     */
    private static PreparedStatement prepare(Connection c, String sql, String customer) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        if (customer != null) ps.setLong(1, Long.parseLong(customer));
        return ps;
    }

    /** One query-string parameter, or null. */
    private static String param(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return null;
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0 && pair.substring(0, i).equals(key)) return pair.substring(i + 1);
        }
        return null;
    }

    private static void subscribe(HttpExchange ex) throws IOException {
        try {
            String body = read(ex);
            String tenant = Json.str(body, "tenant");
            long customer = CallerIdentity.resolve(identity,
                    ex.getRequestHeaders().getFirst("Authorization"),
                    Long.parseLong(Json.str(body, "customer")));
            String variant = Json.str(body, "variant");
            String location = Json.str(body, "location");
            String interval = Json.str(body, "interval_days");
            Instant at = businessAt(body);
            UUID id = Billing.subscribe(tenant, customer, variant, location,
                    interval == null ? 30 : Integer.parseInt(interval), at);
            Billing.Subscription outcome = Billing.lastSubscribeResult();
            // SUBSCRIBE IS IDEMPOTENT, so it may have created nothing and handed
            // back a subscription the customer already had, possibly one in
            // dunning. The first version answered "active" as a hardcoded string
            // without ever looking. The second asked the database how many
            // subscriptions existed before and after, which was a race: another
            // customer subscribing in between made an idempotent no-op report
            // created, on an endpoint a whole agent population hits at once.
            // Now the statement that inserted, or did not, is the one that says.
            boolean created = outcome != null && outcome.created();
            String status = outcome != null ? outcome.status() : statusOf(id);
            send(ex, 200, "{\"subscription\":\"" + id + "\",\"status\":\"" + status
                    + "\",\"created\":" + created + "}");
        } catch (BadBusinessTime e) { send(ex, 400, err(e)); }
        catch (Exception e) { send(ex, 500, err(e)); }
    }

    private static void unsubscribe(HttpExchange ex) throws IOException {
        try {
            String body = read(ex);
            UUID id = UUID.fromString(Json.str(body, "subscription"));
            boolean atPeriodEnd = "true".equals(Json.str(body, "at_period_end"));
            Billing.cancel(id, atPeriodEnd, businessAt(body));
            send(ex, 200, "{\"subscription\":\"" + id + "\",\"canceled\":true,\"at_period_end\":" + atPeriodEnd + "}");
        } catch (BadBusinessTime e) { send(ex, 400, err(e)); }
        catch (Exception e) { send(ex, 500, err(e)); }
    }

    /**
     * The dashboard view, and now also an answerable question.
     *
     * Without the filter this returns the twenty most recent subscriptions
     * across every customer, which is fine for a dashboard and useless as an
     * API: a customer cannot ask what they are subscribed to, and once twenty
     * later subscriptions exist they silently vanish from the answer. The
     * silence is the defect, since an empty result reads exactly like "you have
     * no subscriptions" and a client cannot tell the two apart.
     */
    private static void subscriptions(HttpExchange ex) throws IOException {
        String customer = param(ex, "customer");
        // identity beats the query string: a token naming A means ?customer=B
        // is ignored, not obeyed. Today identity is ANONYMOUS, so the filter
        // behaves exactly as it always has.
        var identified = identity.customerFor(ex.getRequestHeaders().getFirst("Authorization"));
        if (identified.isPresent()) customer = String.valueOf(identified.get());
        String sql = """
                     SELECT s.id, s.customer_id, s.variant_id, s.status, s.period_index, s.next_renewal_at,
                            (SELECT COUNT(*) FROM invoices i WHERE i.subscription_id = s.id AND i.status='paid')
                     FROM subscriptions s
                     """ + (customer == null ? "" : "WHERE s.customer_id = ? ")
                     + "ORDER BY s.business_at DESC LIMIT " + (customer == null ? "20" : "200");
        try (Connection c = Db.open();
             PreparedStatement ps = prepare(c, sql, customer);
             ResultSet rs = ps.executeQuery()) {
            StringBuilder b = new StringBuilder("[");
            boolean first = true;
            while (rs.next()) {
                if (!first) b.append(',');
                first = false;
                b.append("{\"id\":\"").append(rs.getString(1))
                 .append("\",\"customer\":").append(rs.getLong(2))
                 .append(",\"variant\":\"").append(Json.esc(rs.getString(3)))
                 .append("\",\"status\":\"").append(rs.getString(4))
                 .append("\",\"period\":").append(rs.getInt(5))
                 .append(",\"nextRenewal\":\"").append(rs.getTimestamp(6).toInstant())
                 .append("\",\"paid\":").append(rs.getLong(7)).append('}');
            }
            send(ex, 200, b.append(']').toString());
        } catch (Exception e) { send(ex, 500, err(e)); }
    }

    // -------------------------------------------------------- the simulation

    private static final java.util.concurrent.atomic.AtomicReference<String> SIM_STATE =
            new java.util.concurrent.atomic.AtomicReference<>("{\"running\":false,\"message\":\"idle\"}");

    /** Launch a population in the background so the page can watch it shop. */
    private static void simRun(HttpExchange ex) throws IOException {
        try {
            String body = read(ex);
            int agents = intOr(Json.str(body, "agents"), 25);
            int ticks = intOr(Json.str(body, "ticks"), 24);
            if (SIM_STATE.get().contains("\"running\":true")) {
                send(ex, 409, "{\"error\":\"a run is already in progress\"}"); return;
            }
            int port = ex.getLocalAddress().getPort();
            SIM_STATE.set("{\"running\":true,\"message\":\"starting " + agents + " agents\"}");
            Thread.ofVirtual().start(() -> {
                try {
                    var sim = new dev.minimart.sim.SimRunner("http://localhost:" + port);
                    var r = sim.run("web-" + System.nanoTime(), agents, ticks, "helix", "MAD",
                            java.time.Instant.now(), java.time.Duration.ofHours(1));
                    SIM_STATE.set("{\"running\":false,\"message\":\"done\",\"agents\":" + agents +
                            ",\"ticks\":" + ticks + ",\"placed\":" + r.placed() + ",\"shipped\":" + r.shipped() +
                            ",\"released\":" + r.released() + "}");
                } catch (Exception e) {
                    SIM_STATE.set("{\"running\":false,\"message\":\"failed: " +
                            Json.esc(String.valueOf(e.getMessage())) + "\"}");
                }
            });
            send(ex, 200, "{\"started\":true}");
        } catch (Exception e) { send(ex, 500, err(e)); }
    }

    private static void simStatus(HttpExchange ex) throws IOException { send(ex, 200, SIM_STATE.get()); }

    private static int intOr(String s, int fallback) {
        try { return s == null ? fallback : Math.min(200, Math.max(1, Integer.parseInt(s))); }
        catch (Exception e) { return fallback; }
    }

    private static long bal(Connection c, String ref) {
        try { return Ledger.balance(c, ref).longValue(); } catch (Exception e) { return 0; }
    }

    private static long one(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next(); return rs.getLong(1);
        }
    }

    private static void catalog(HttpExchange ex) throws IOException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, tenant, title, price FROM variants ORDER BY id")) {
            StringBuilder b = new StringBuilder("[");
            try (ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) b.append(',');
                    first = false;
                    b.append("{\"id\":\"").append(Json.esc(rs.getString(1)))
                     .append("\",\"tenant\":\"").append(Json.esc(rs.getString(2)))
                     .append("\",\"title\":\"").append(Json.esc(rs.getString(3)))
                     .append("\",\"price\":\"").append(rs.getBigDecimal(4).stripTrailingZeros().toPlainString())
                     .append("\"}");
                }
            }
            send(ex, 200, b.append(']').toString());
        } catch (Exception e) {
            send(ex, 500, err(e));
        }
    }

    /** Reserve goods here, charge at the processor, compensate if that fails. */
    private static void checkout(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"POST only\"}"); return; }
            String body = read(ex);
            String orderId = Json.str(body, "orderId");
            String tenant = Json.str(body, "tenant");
            String customer = Json.str(body, "customer");
            String variant = Json.str(body, "variant");
            String location = Json.str(body, "location");
            String qty = Json.str(body, "qty");
            if (orderId == null || tenant == null || customer == null || variant == null || qty == null) {
                send(ex, 400, "{\"error\":\"need orderId, tenant, customer, variant, qty\"}"); return;
            }
            // identity beats the body: a token naming A means a body naming B
            // is ignored, not obeyed. Today identity is ANONYMOUS, so this
            // resolves to the body's customer exactly as before.
            long actingCustomer = CallerIdentity.resolve(identity,
                ex.getRequestHeaders().getFirst("Authorization"), Long.parseLong(customer));
            Checkout.Result r = Checkout.place(UUID.fromString(orderId), tenant, actingCustomer,
                    variant, location == null ? "MAD" : location, Long.parseLong(qty), businessAt(body));
            if (r instanceof Checkout.Placed p) {
                send(ex, 200, "{\"orderId\":\"" + p.orderId() + "\",\"payment_intent\":\"" + p.paymentIntentId() +
                        "\",\"amount\":\"" + p.amount().stripTrailingZeros().toPlainString() + "\",\"state\":\"reserved\"}");
            } else {
                send(ex, 409, "{\"error\":\"" + Json.esc(((Checkout.Rejected) r).reason()) + "\"}");
            }
        } catch (Exception e) {
            send(ex, 500, err(e));
        }
    }

    /** /api/orders/{id} · GET, or POST {id}/ship, {id}/cancel */
    private static void orders(HttpExchange ex) throws IOException {
        try {
            String rest = ex.getRequestURI().getPath().substring("/api/orders".length());
            if (rest.startsWith("/")) rest = rest.substring(1);
            if (rest.isEmpty()) { send(ex, 400, "{\"error\":\"need an order id\"}"); return; }
            String[] parts = rest.split("/");
            UUID id = UUID.fromString(parts[0]);
            if (parts.length == 1) { send(ex, 200, orderJson(id)); return; }
            String body = read(ex);
            Instant at = businessAt(body);
            switch (parts[1]) {
                case "ship" -> Checkout.ship(id, at);
                case "cancel" -> Checkout.cancel(id, at);
                default -> { send(ex, 404, "{\"error\":\"no such action\"}"); return; }
            }
            send(ex, 200, orderJson(id));
        } catch (Exception e) {
            send(ex, 500, err(e));
        }
    }

    /** Goods arrive from a supplier. */
    private static void stock(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"POST only\"}"); return; }
            String body = read(ex);
            Orders.receiveStock(Json.str(body, "tenant"), Json.str(body, "location"),
                    Json.str(body, "variant"), Long.parseLong(Json.str(body, "qty")), businessAt(body));
            send(ex, 200, "{\"ok\":true}");
        } catch (Exception e) {
            send(ex, 500, err(e));
        }
    }

    /** One deterministic pass of every time-driven job, at a tick boundary. */
    private static void tick(HttpExchange ex) throws IOException {
        try {
            Instant at = businessAt(read(ex));
            int released = ReservationSweeper.sweepOnce(at, 500);
            // billing is time-driven too, so it belongs to the tick rather than
            // to a background timer: the same pass, the same business instant,
            // and a compressed run stays reproducible
            Billing.Report r = Billing.renewOnce(at, 200);
            send(ex, 200, "{\"business_at\":\"" + at + "\",\"reservations_released\":" + released
                    + ",\"renewed\":" + r.renewed() + ",\"failed\":" + r.failed()
                    + ",\"recovered\":" + r.recovered() + ",\"given_up\":" + r.givenUp()
                    // reported, never swallowed: a skip means the system failed
                    // somebody, and the only thing worse than that is not saying so
                    + ",\"skipped\":" + r.skipped() + "}");
        } catch (Exception e) {
            send(ex, 500, err(e));
        }
    }

    /** The three audits, live. Zero rows everywhere means the books are honest. */
    private static void audit(HttpExchange ex) throws IOException {
        try (Connection c = Db.open()) {
            var sumZero = Ledger.sumZeroViolations(c);
            var drift = Ledger.driftedAccounts(c);
            var reserved = ReservationSweeper.reservedMismatches(c);
            send(ex, 200, "{\"sum_zero_violations\":" + sumZero.size() +
                    ",\"drifted_accounts\":" + drift.size() +
                    ",\"reserved_mismatches\":" + reserved.size() +
                    ",\"healthy\":" + (sumZero.isEmpty() && drift.isEmpty() && reserved.isEmpty()) + "}");
        } catch (Exception e) {
            send(ex, 500, err(e));
        }
    }

    private static String orderJson(UUID id) throws Exception {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT state, amount, qty, variant_id, payment_mode, payment_intent_id FROM orders WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "{\"error\":\"no such order\"}";
                return "{\"id\":\"" + id + "\",\"state\":\"" + rs.getString(1) +
                       "\",\"amount\":\"" + rs.getBigDecimal(2).stripTrailingZeros().toPlainString() +
                       "\",\"qty\":" + rs.getLong(3) +
                       ",\"variant\":\"" + Json.esc(rs.getString(4)) +
                       "\",\"payment_mode\":\"" + rs.getString(5) +
                       "\",\"payment_intent\":\"" + Json.esc(String.valueOf(rs.getString(6))) + "\"}";
            }
        }
    }

    /** Raised when business_at is present but unreadable. Deliberately a
     *  distinct type, so the endpoints can answer 400 rather than folding it in
     *  with every other failure as a 500. */
    static final class BadBusinessTime extends RuntimeException {
        BadBusinessTime(String value) {
            super("business_at is not an ISO-8601 instant: " + value);
        }
    }

    /**
     * ABSENT MEANS NOW. UNREADABLE MEANS NO.
     *
     * The doctrine of this codebase is that time is a parameter and no business
     * logic reads the wall clock, which is what lets a compressed year run in
     * minutes. The first version broke it in a catch block: a business_at it
     * could not parse was silently replaced with Instant.now(), with no error
     * and no log.
     *
     * That is the worst available outcome. A client with a date-formatting bug
     * gets a run that appears to work, is quietly pinned to the wall clock, and
     * produces results nobody can reproduce, with nothing anywhere saying why.
     * A missing timestamp still defaults to now, because a browser genuinely has
     * no business time to send. A malformed one is a caller bug and is told so.
     */
    private static Instant businessAt(String body) {
        String at = body == null ? null : Json.str(body, "business_at");
        if (at == null || at.isBlank()) return Instant.now();
        try { return Instant.parse(at); }
        catch (Exception e) { throw new BadBusinessTime(at); }
    }

    private static String statusOf(UUID subscriptionId) throws SQLException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("SELECT status FROM subscriptions WHERE id = ?")) {
            ps.setObject(1, subscriptionId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : "unknown"; }
        }
    }

    private static String err(Exception e) {
        return "{\"error\":\"" + Json.esc(String.valueOf(e.getMessage())) + "\"}";
    }

    private static String read(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
