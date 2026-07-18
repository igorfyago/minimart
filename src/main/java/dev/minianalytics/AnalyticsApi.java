package dev.minianalytics;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.minimart.core.Json;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Analytics has its own port, because it is its own service.
 *
 * Every number it returns is a SUM OF MOVEMENTS, computed at read time. There
 * is no cached total anywhere in this file, which is what makes /movements a
 * genuine explanation of /mrr rather than a plausible story told alongside it.
 */
public final class AnalyticsApi {

    private AnalyticsApi() {}

    public static HttpServer start(int port) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.createContext("/api/analytics/mrr", AnalyticsApi::mrr);
        s.createContext("/api/analytics/movements", AnalyticsApi::movements);
        s.createContext("/api/analytics/waterfall", AnalyticsApi::waterfall);
        s.createContext("/api/analytics/health", AnalyticsApi::health);
        s.start();
        return s;
    }

    /** The headline number, and the two independent paths to it. */
    private static void mrr(HttpExchange ex) throws IOException {
        try {
            String tenant = param(ex, "tenant", "helix");
            BigDecimal fromMovements = Projection.mrr(tenant);
            BigDecimal fromState = Projection.mrrFromState(tenant);
            StringBuilder b = new StringBuilder("{");
            b.append("\"tenant\":\"").append(Json.esc(tenant)).append("\",");
            b.append("\"mrr\":").append(fromMovements).append(',');
            b.append("\"mrr_from_projection\":").append(fromState).append(',');
            // if this is ever non-zero the projection has drifted and the
            // movements are the ones to believe. Exposed, not hidden.
            b.append("\"drift\":").append(fromMovements.subtract(fromState)).append(',');
            try (Connection c = AnalyticsDb.open()) {
                b.append("\"active_subscriptions\":").append(
                        count(c, "SELECT COUNT(*) FROM subscription_state WHERE tenant = '" + esc(tenant) + "' AND status = 'active'")).append(',');
                b.append("\"events_handled\":").append(count(c, "SELECT COUNT(*) FROM handled_events")).append(',');
                b.append("\"collected\":").append(dec(c,
                        "SELECT COALESCE(SUM(collected),0) FROM subscription_state WHERE tenant = '" + esc(tenant) + "'")).append(',');
                b.append("\"renewals\":").append(count(c,
                        "SELECT COALESCE(SUM(renewals),0) FROM subscription_state WHERE tenant = '" + esc(tenant) + "'"));
            }
            send(ex, 200, b.append('}').toString());
        } catch (Exception e) { send(ex, 500, err(e)); }
    }

    /** Every movement, newest first. This is the audit trail behind the number. */
    private static void movements(HttpExchange ex) throws IOException {
        try (Connection c = AnalyticsDb.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT kind, amount, subscription_id, customer_id, variant_id,
                            provenance, event_key, business_at
                       FROM mrr_movements WHERE tenant = ?
                      ORDER BY business_at DESC, id DESC LIMIT 60""")) {
            ps.setString(1, param(ex, "tenant", "helix"));
            StringBuilder b = new StringBuilder("{\"movements\":[");
            try (ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) b.append(',');
                    first = false;
                    b.append("{\"kind\":\"").append(rs.getString(1)).append('"')
                     .append(",\"amount\":").append(rs.getBigDecimal(2))
                     .append(",\"subscription_id\":\"").append(rs.getString(3)).append('"')
                     .append(",\"customer_id\":").append(rs.getLong(4))
                     .append(",\"variant_id\":\"").append(Json.esc(rs.getString(5))).append('"')
                     .append(",\"provenance\":\"").append(rs.getString(6)).append('"')
                     .append(",\"event_key\":\"").append(Json.esc(rs.getString(7))).append('"')
                     .append(",\"business_at\":\"").append(rs.getTimestamp(8).toInstant()).append("\"}");
                }
            }
            send(ex, 200, b.append("]}").toString());
        } catch (Exception e) { send(ex, 500, err(e)); }
    }

    /** MRR broken into where it came from and where it went. */
    private static void waterfall(HttpExchange ex) throws IOException {
        try (Connection c = AnalyticsDb.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT kind, COALESCE(SUM(amount),0), COUNT(*)
                       FROM mrr_movements WHERE tenant = ? GROUP BY kind""")) {
            ps.setString(1, param(ex, "tenant", "helix"));
            List<String> parts = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    parts.add("{\"kind\":\"" + rs.getString(1) + "\",\"amount\":" + rs.getBigDecimal(2)
                            + ",\"count\":" + rs.getLong(3) + "}");
                }
            }
            send(ex, 200, "{\"waterfall\":[" + String.join(",", parts) + "]}");
        } catch (Exception e) { send(ex, 500, err(e)); }
    }

    private static void health(HttpExchange ex) throws IOException {
        try (Connection c = AnalyticsDb.open()) {
            send(ex, 200, "{\"service\":\"minianalytics\",\"database\":\"minimart_analytics\""
                    + ",\"consumer_group\":\"" + AnalyticsConsumer.GROUP + "\""
                    + ",\"events_handled\":" + count(c, "SELECT COUNT(*) FROM handled_events")
                    + ",\"movements\":" + count(c, "SELECT COUNT(*) FROM mrr_movements") + "}");
        } catch (Exception e) { send(ex, 500, err(e)); }
    }

    // ---------------------------------------------------------------- plumbing

    private static long count(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next(); return rs.getLong(1);
        }
    }

    private static BigDecimal dec(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next(); return rs.getBigDecimal(1);
        }
    }

    private static String esc(String s) { return s.replace("'", "''"); }

    private static String param(HttpExchange ex, String key, String fallback) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return fallback;
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0 && pair.substring(0, i).equals(key)) return pair.substring(i + 1);
        }
        return fallback;
    }

    private static String err(Exception e) {
        return "{\"error\":\"" + Json.esc(String.valueOf(e.getMessage())) + "\"}";
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
