package dev.minifreight;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.minimart.core.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * FREIGHT'S DOOR · what carriers may tell it, and what anyone may ask it.
 *
 * The webhook is the only write reachable from outside, and it is treated as
 * what it is: an unauthenticated internet endpoint whose payload completes
 * orders. So nothing in the body is believed until the signature over the
 * exact bytes verifies · HMAC-SHA256, per-carrier secret, constant-time
 * compare. A forged "delivered" is not a cosmetic problem: downstream of that
 * word, money stops being owed back.
 *
 * A verified duplicate and a verified stale answer both get 200. The carrier
 * is reporting a fact we already hold, and an error would only teach its
 * retry loop to repeat itself forever.
 */
public final class FreightApi {

    /** Shared with each carrier at onboarding. Overridable per environment,
     *  constant by default, and the same either way: possession of this
     *  string IS the carrier's identity as far as the webhook is concerned. */
    static final Map<String, String> SECRETS = Map.of(
            "swift", env("FREIGHT_SWIFT_SECRET", "swift-hook-secret-01"),
            "budget", env("FREIGHT_BUDGET_SECRET", "budget-hook-secret-01"));

    private FreightApi() {}

    public static HttpServer start(int port) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
        s.createContext("/webhooks/carrier/", FreightApi::webhook);
        s.createContext("/api/shipments", FreightApi::shipments);
        s.createContext("/api/freight/audit", FreightApi::audit);
        s.createContext("/api/freight/shipments/", FreightApi::ops);
        s.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        s.start();
        return s;
    }

    // ------------------------------------------------------------- webhooks

    private static void webhook(HttpExchange x) throws IOException {
        try {
            String carrier = x.getRequestURI().getPath().substring("/webhooks/carrier/".length());
            String secret = SECRETS.get(carrier);
            if (secret == null) { respond(x, 404, "{\"error\":\"unknown carrier\"}"); return; }

            byte[] raw = x.getRequestBody().readAllBytes();
            String body = new String(raw, StandardCharsets.UTF_8);
            String given = x.getRequestHeaders().getFirst("X-Carrier-Signature");
            if (given == null || !MessageDigest.isEqual(
                    given.getBytes(StandardCharsets.UTF_8),
                    hmac(secret, body).getBytes(StandardCharsets.UTF_8))) {
                respond(x, 401, "{\"error\":\"bad signature\"}");
                return;
            }

            String tracking = Json.str(body, "tracking");
            String status = Json.str(body, "status");
            String eventKey = Json.str(body, "eventKey");
            if (tracking == null || status == null || eventKey == null || Shipments.rank(status) < 0) {
                respond(x, 400, "{\"error\":\"tracking, status and eventKey required\"}");
                return;
            }

            try (Connection c = FreightDb.open()) {
                c.setAutoCommit(false);
                UUID shipmentId = Shipments.byTracking(c, carrier, tracking);
                if (shipmentId == null) {
                    // signed, but about a parcel we never labelled: the
                    // carrier's bug, and a 404 it should hear about
                    c.rollback();
                    respond(x, 404, "{\"error\":\"no such parcel\"}");
                    return;
                }
                Shipments.Advance result = Shipments.advance(c, shipmentId, carrier, tracking,
                        status, eventKey, Instant.now());
                c.commit();
                respond(x, 200, Json.obj("result", result.name().toLowerCase()));
            }
        } catch (Exception e) {
            respond(x, 500, "{\"error\":\"" + Json.esc(e.getMessage()) + "\"}");
        }
    }

    // -------------------------------------------------------------- reading

    private static void shipments(HttpExchange x) throws IOException {
        try (Connection c = FreightDb.open()) {
            String path = x.getRequestURI().getPath();
            String query = x.getRequestURI().getQuery();
            if (path.equals("/api/shipments") && query != null && query.startsWith("order=")) {
                UUID orderId = UUID.fromString(query.substring("order=".length()));
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id FROM shipments WHERE order_id = ?")) {
                    ps.setObject(1, orderId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) { respond(x, 404, "{\"error\":\"no shipment for that order\"}"); return; }
                        respond(x, 200, describe(c, (UUID) rs.getObject(1)));
                    }
                }
                return;
            }
            String[] p = path.split("/");
            if (p.length == 4) { respond(x, 200, describe(c, UUID.fromString(p[3]))); return; }
            respond(x, 404, "{}");
        } catch (IllegalArgumentException e) {
            respond(x, 400, "{\"error\":\"not a uuid\"}");
        } catch (Exception e) {
            respond(x, 500, "{\"error\":\"" + Json.esc(e.getMessage()) + "\"}");
        }
    }

    /** The shipment, its journal and its tracking history in one answer,
     *  because "what happened to my parcel" is one question. */
    private static String describe(Connection c, UUID id) throws Exception {
        StringBuilder b = new StringBuilder();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT order_id, tenant, variant, qty, destination, state, carrier, tracking_ref, attempts
                FROM shipments WHERE id = ?""")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "{\"error\":\"no such shipment\"}";
                b.append("{\"shipmentId\":\"").append(id)
                        .append("\",\"orderId\":\"").append(rs.getString(1))
                        .append("\",\"tenant\":\"").append(Json.esc(rs.getString(2)))
                        .append("\",\"variant\":\"").append(Json.esc(rs.getString(3)))
                        .append("\",\"qty\":\"").append(rs.getLong(4))
                        .append("\",\"destination\":\"").append(Json.esc(rs.getString(5)))
                        .append("\",\"state\":\"").append(rs.getString(6))
                        .append("\",\"carrier\":\"").append(rs.getString(7) == null ? "" : rs.getString(7))
                        .append("\",\"tracking\":\"").append(rs.getString(8) == null ? "" : rs.getString(8))
                        .append("\",\"attempts\":\"").append(rs.getInt(9)).append("\"");
            }
        }
        b.append(",\"steps\":[");
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT carrier, state, detail FROM carrier_steps WHERE shipment_id = ? ORDER BY id")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) b.append(',');
                    first = false;
                    b.append(Json.obj("carrier", rs.getString(1), "state", rs.getString(2),
                            "detail", rs.getString(3) == null ? "" : rs.getString(3)));
                }
            }
        }
        b.append("],\"tracking_events\":[");
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT carrier, status, event_key FROM tracking_events WHERE shipment_id = ? ORDER BY id")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) b.append(',');
                    first = false;
                    b.append(Json.obj("carrier", rs.getString(1), "status", rs.getString(2),
                            "eventKey", rs.getString(3)));
                }
            }
        }
        return b.append("]}").toString();
    }

    /**
     * The numbers a person on call would actually want, one query each: how
     * many parcels sit in each state, how old the oldest open one is, and how
     * many journal entries currently mean "we do not know". A freight service
     * that cannot answer these is a demo wearing a uniform.
     */
    private static void audit(HttpExchange x) throws IOException {
        try (Connection c = FreightDb.open()) {
            StringBuilder b = new StringBuilder("{\"states\":{");
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT state, COUNT(*) FROM shipments GROUP BY state ORDER BY state");
                 ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) b.append(',');
                    first = false;
                    b.append('"').append(rs.getString(1)).append("\":\"").append(rs.getLong(2)).append('"');
                }
            }
            b.append("},");
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT COALESCE(EXTRACT(EPOCH FROM now() - MIN(created_at)), 0)::bigint
                    FROM shipments WHERE state IN ('requested','labelled','in_transit')""");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                b.append("\"oldest_open_seconds\":\"").append(rs.getLong(1)).append("\",");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM carrier_steps WHERE state IN ('requested','unknown')");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                b.append("\"steps_with_unknown_outcome\":\"").append(rs.getLong(1)).append("\",");
            }
            b.append("\"consumer_unactionable\":\"").append(FreightConsumer.unactionable.get()).append("\"}");
            respond(x, 200, b.toString());
        } catch (Exception e) {
            respond(x, 500, "{\"error\":\"" + Json.esc(e.getMessage()) + "\"}");
        }
    }

    /** POST /api/freight/shipments/{id}/resolve · the human end of 'stuck'.
     *
     *  This estate's demos run without login on purpose, but a repair endpoint
     *  is not a demo: it moves a shipment to a verdict, and downstream of one
     *  of those verdicts money stops being owed back. So the door takes a
     *  token when one is configured · FREIGHT_OPS_TOKEN, compared in constant
     *  time · and the unset default is honest about being a development
     *  posture, not a production one. */
    private static void ops(HttpExchange x) throws IOException {
        try {
            String[] p = x.getRequestURI().getPath().split("/");
            if (p.length != 6 || !"resolve".equals(p[5]) || !"POST".equals(x.getRequestMethod())) {
                respond(x, 404, "{}");
                return;
            }
            String required = env("FREIGHT_OPS_TOKEN", "");
            if (!required.isBlank()) {
                String given = x.getRequestHeaders().getFirst("X-Ops-Token");
                if (given == null || !MessageDigest.isEqual(
                        given.getBytes(StandardCharsets.UTF_8), required.getBytes(StandardCharsets.UTF_8))) {
                    respond(x, 401, "{\"error\":\"ops token required\"}");
                    return;
                }
            }
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String verdict = Json.str(body, "verdict");
            if (verdict == null) { respond(x, 400, "{\"error\":\"verdict required: failed or delivered\"}"); return; }
            try (Connection c = FreightDb.open()) {
                c.setAutoCommit(false);
                boolean moved = Shipments.resolve(c, UUID.fromString(p[4]), verdict,
                        Json.str(body, "note"), Instant.now());
                c.commit();
                if (moved) respond(x, 200, Json.obj("result", "resolved", "verdict", verdict));
                else respond(x, 409, "{\"error\":\"only a stuck shipment is a human's to move\"}");
            }
        } catch (IllegalArgumentException e) {
            respond(x, 400, "{\"error\":\"" + Json.esc(e.getMessage()) + "\"}");
        } catch (Exception e) {
            respond(x, 500, "{\"error\":\"" + Json.esc(e.getMessage()) + "\"}");
        }
    }

    /** Deliberately NOT shared with CarrierSim, for the reason the Json codec
     *  is duplicated per service: the carrier and freight agreeing about bytes
     *  is not the same thing as them sharing a class, and the day the sim is
     *  replaced by a real carrier, this method is the one that stays. */
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

    private static String env(String k, String fallback) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static void respond(HttpExchange x, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = x.getResponseBody()) { os.write(bytes); }
    }
}
