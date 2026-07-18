package dev.minipay;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * TELLING THE ISSUER WHAT WE ACTUALLY COMPLETED.
 *
 * Authorisation is real time because a customer is standing there. Clearing is
 * not: it is the acquirer telling the issuer, later and in bulk, which of those
 * authorisations it went on to complete, and it is where two banks work out
 * what one owes the other.
 *
 * That division is the whole architecture of card payments in one line, and it
 * is why this system uses both a synchronous call and a broker: SYNCHRONOUS
 * WHERE SOMEBODY IS WAITING FOR A DECISION, ASYNCHRONOUS EVERYWHERE ELSE. A
 * system that clears synchronously has not exactly made a mistake, but it has
 * built something that cannot survive a slow issuer, cannot batch, and has
 * nowhere to put interchange.
 *
 * INTERCHANGE is what makes this worth modelling. The issuer does not hand over
 * the full amount: it keeps a fee for having lent the customer the money and
 * carried the risk, and the acquirer receives the rest. So gross, interchange
 * and net are three numbers that two organisations sharing no database must
 * agree on, and the only honest way to know they agree is for both to compute
 * them independently and compare.
 *
 * WHAT THIS DELIBERATELY DOES NOT DO: pretend the issuer's agreement is
 * automatic. The batch is built, submitted, and only then acknowledged, and
 * the issuer's own total is stored NEXT TO ours rather than over it. Overwriting
 * ours with theirs would destroy the only evidence that they ever matched.
 */
public final class Clearing {

    /**
     * The issuer's cut. A real interchange rate is a matrix of card type,
     * merchant category and geography; one number here is a deliberate
     * simplification and is worth naming as one rather than implying that
     * interchange is simple.
     */
    public static volatile BigDecimal interchangeRate = new BigDecimal("0.008");

    public record Batch(String id, String issuer, String currency, LocalDate businessDate,
                        BigDecimal gross, BigDecimal interchange, BigDecimal net,
                        int items, String state, BigDecimal issuerNet) {

        /** Whether the two banks independently arrived at the same number. The
         *  only question a clearing batch exists to answer. */
        public boolean agreed() {
            return issuerNet != null && net.compareTo(issuerNet) == 0;
        }
    }

    /** Where the affiliated issuer accepts clearing. Configuration, never a host in code. */
    public static volatile String issuerBaseUrl =
            System.getenv().getOrDefault("MINIBANK_ISSUER_URL", "http://localhost:8080");

    public static volatile boolean issuerUnreachable = false;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    private Clearing() {}

    public static BigDecimal interchangeFor(BigDecimal amount) {
        return amount.multiply(interchangeRate).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Build the day's batch of completed card payments.
     *
     * Only ON_US payments are here, because those are the ones whose money sits
     * at an issuer we can clear with. A wallet payment never left this building
     * and has nothing to clear.
     *
     * Returns null when there is nothing to clear, which is not an error: an
     * empty batch would be noise in a report somebody has to read.
     */
    public static Batch build(String issuer, String currency, LocalDate businessDate, Instant at)
            throws SQLException {
        record Item(String intentId, String authRef, BigDecimal amount) {}

        try (Connection c = PayDb.open()) {
            c.setAutoCommit(false);
            try {
                List<Item> items = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement("""
                        SELECT id, issuer_authorization, amount FROM payment_intents
                         WHERE rail = 'ON_US' AND currency = ? AND status = 'succeeded'
                           AND clearing_batch IS NULL AND issuer_authorization IS NOT NULL
                           AND business_at >= ? AND business_at < ?
                         ORDER BY id
                         FOR UPDATE""")) {
                    ps.setString(1, currency);
                    ps.setTimestamp(2, java.sql.Timestamp.from(businessDate.atStartOfDay(ZoneOffset.UTC).toInstant()));
                    ps.setTimestamp(3, java.sql.Timestamp.from(businessDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) items.add(new Item(rs.getString(1), rs.getString(2), rs.getBigDecimal(3)));
                    }
                }
                if (items.isEmpty()) { c.rollback(); return null; }

                BigDecimal gross = BigDecimal.ZERO, interchange = BigDecimal.ZERO;
                for (Item i : items) {
                    gross = gross.add(i.amount());
                    interchange = interchange.add(interchangeFor(i.amount()));
                }
                BigDecimal net = gross.subtract(interchange);

                String id = "cb_" + UUID.nameUUIDFromBytes(
                                (issuer + ':' + currency + ':' + businessDate).getBytes(StandardCharsets.UTF_8))
                        .toString().replace("-", "").substring(0, 16);

                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO clearing_batches(id, issuer, currency, business_date, gross, interchange,
                                                     net, item_count, business_at)
                        VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (issuer, currency, business_date) DO NOTHING""")) {
                    ps.setString(1, id); ps.setString(2, issuer); ps.setString(3, currency);
                    ps.setObject(4, businessDate);
                    ps.setBigDecimal(5, gross); ps.setBigDecimal(6, interchange); ps.setBigDecimal(7, net);
                    ps.setInt(8, items.size()); ps.setTimestamp(9, java.sql.Timestamp.from(at));
                    if (ps.executeUpdate() == 0) { c.rollback(); return null; }   // already cleared today
                }

                for (Item i : items) {
                    try (PreparedStatement ps = c.prepareStatement("""
                            INSERT INTO clearing_items(batch_id, payment_intent_id, authorization_ref, amount, interchange)
                            VALUES (?,?,?,?,?)""")) {
                        ps.setString(1, id); ps.setString(2, i.intentId()); ps.setString(3, i.authRef());
                        ps.setBigDecimal(4, i.amount()); ps.setBigDecimal(5, interchangeFor(i.amount()));
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE payment_intents SET clearing_batch = ? WHERE id = ?")) {
                        ps.setString(1, id); ps.setString(2, i.intentId());
                        ps.executeUpdate();
                    }
                }
                c.commit();
                return new Batch(id, issuer, currency, businessDate, gross, interchange, net,
                        items.size(), "built", null);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /**
     * Send the batch to the issuer and record what it said.
     *
     * The issuer's total is stored beside ours, never over it. Two parties
     * computing the same number independently is the entire mechanism: if the
     * acquirer simply adopted whatever the issuer replied, the reconciliation
     * would always pass and would mean nothing.
     *
     * Submitting twice is harmless. The batch is identified by issuer, currency
     * and day, so the issuer recognises a repeat and answers the same way.
     */
    public static Batch submit(String batchId, Instant at) throws SQLException {
        Batch b = find(batchId);
        if (b == null) return null;
        if ("acknowledged".equals(b.state())) return b;    // already done

        StringBuilder lines = new StringBuilder();
        try (Connection c = PayDb.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT authorization_ref, amount FROM clearing_items WHERE batch_id = ? ORDER BY payment_intent_id")) {
            ps.setString(1, batchId);
            try (ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) lines.append(',');
                    first = false;
                    lines.append("{\"authorization\":\"").append(rs.getString(1))
                         .append("\",\"amount\":\"").append(rs.getBigDecimal(2).toPlainString()).append("\"}");
                }
            }
        }

        String body = "{\"batch\":\"" + b.id() + "\",\"currency\":\"" + b.currency()
                + "\",\"business_date\":\"" + b.businessDate()
                + "\",\"gross\":\"" + b.gross().toPlainString()
                + "\",\"interchange\":\"" + b.interchange().toPlainString()
                + "\",\"net\":\"" + b.net().toPlainString()
                + "\",\"business_at\":\"" + at + "\",\"items\":[" + lines + "]}";

        String response = post("/issuer/v1/clearing", body);
        if (response == null) {
            // The issuer could not be told. The batch stays SUBMITTED and
            // unacknowledged, which is an honest description of what is known:
            // we tried, and we do not know whether they have it. It is retried
            // later, and submitting twice is harmless by design.
            mark(batchId, "submitted", null, null, at);
            return find(batchId);
        }
        String issuerNet = dev.minimart.core.Json.str(response, "net");
        String issuerRef = dev.minimart.core.Json.str(response, "reference");
        mark(batchId, "acknowledged", issuerNet == null ? null : new BigDecimal(issuerNet), issuerRef, at);
        return find(batchId);
    }

    /**
     * AUDIT · every completed card payment is in exactly one batch, or is
     * waiting to be.
     *
     * The number that matters is not how much cleared but how much has been
     * captured and never told to anybody: that is money the merchant has been
     * promised and the issuer has not yet been asked for.
     */
    public static BigDecimal uncleared(String currency) throws SQLException {
        try (Connection c = PayDb.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT COALESCE(SUM(amount), 0) FROM payment_intents
                      WHERE rail = 'ON_US' AND currency = ? AND status = 'succeeded'
                        AND clearing_batch IS NULL""")) {
            ps.setString(1, currency);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getBigDecimal(1); }
        }
    }

    // ------------------------------------------------------------------ internals

    public static Batch find(String batchId) throws SQLException {
        try (Connection c = PayDb.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id, issuer, currency, business_date, gross, interchange, net,
                            item_count, state, issuer_net
                       FROM clearing_batches WHERE id = ?""")) {
            ps.setString(1, batchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Batch(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, LocalDate.class), rs.getBigDecimal(5), rs.getBigDecimal(6),
                        rs.getBigDecimal(7), rs.getInt(8), rs.getString(9), rs.getBigDecimal(10));
            }
        }
    }

    private static void mark(String batchId, String state, BigDecimal issuerNet, String issuerRef, Instant at)
            throws SQLException {
        try (Connection c = PayDb.open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE clearing_batches
                        SET state = ?, issuer_net = COALESCE(?, issuer_net), issuer_ref = COALESCE(?, issuer_ref),
                            submitted_at = COALESCE(submitted_at, ?),
                            acknowledged_at = CASE WHEN ? = 'acknowledged' THEN ? ELSE acknowledged_at END
                      WHERE id = ?""")) {
            ps.setString(1, state);
            if (issuerNet == null) ps.setNull(2, java.sql.Types.NUMERIC); else ps.setBigDecimal(2, issuerNet);
            ps.setString(3, issuerRef);
            ps.setTimestamp(4, java.sql.Timestamp.from(at));
            ps.setString(5, state);
            ps.setTimestamp(6, java.sql.Timestamp.from(at));
            ps.setString(7, batchId);
            ps.executeUpdate();
        }
    }

    private static String post(String path, String body) {
        if (issuerUnreachable) return null;
        try {
            HttpResponse<String> r = HTTP.send(
                    HttpRequest.newBuilder(URI.create(issuerBaseUrl + path))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 ? r.body() : null;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }
}
