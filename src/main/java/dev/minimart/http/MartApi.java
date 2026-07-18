package dev.minimart.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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

    public static HttpServer start(int port) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.createContext("/api/catalog", MartApi::catalog);
        s.createContext("/api/checkout", MartApi::checkout);
        s.createContext("/api/orders", MartApi::orders);
        s.createContext("/api/stock", MartApi::stock);
        s.createContext("/api/sim/tick", MartApi::tick);
        s.createContext("/api/audit", MartApi::audit);
        s.start();
        return s;
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
            Checkout.Result r = Checkout.place(UUID.fromString(orderId), tenant, Long.parseLong(customer),
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
            send(ex, 200, "{\"business_at\":\"" + at + "\",\"reservations_released\":" + released + "}");
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

    private static Instant businessAt(String body) {
        String at = body == null ? null : Json.str(body, "business_at");
        try { return at == null ? Instant.now() : Instant.parse(at); }
        catch (Exception e) { return Instant.now(); }
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
