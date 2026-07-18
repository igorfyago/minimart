package dev.minianalytics;

import dev.minimart.core.Json;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/**
 * WHAT THE EVENTS MEANT.
 *
 * The whole service is this one function: take an event, decide what it did to
 * recurring revenue, and write that down as a movement. Two rules carry most of
 * the weight, and both are places where an eager implementation gets it wrong.
 *
 * A RENEWAL IS NOT NEW MRR. It is cash, and it is proof the subscription is
 * alive, but the revenue was already counted the month it began. Adding it
 * again on every renewal is the classic way a dashboard shows a business
 * growing 30% a month while the bank balance says otherwise. Renewals move
 * MRR only when the PRICE moved, and then only by the difference.
 *
 * MRR IS NORMALISED TO A MONTH. A yearly plan at 1200 is not 1200 of MRR, it
 * is 100. Mixing intervals in one total is how the same number means different
 * things to finance and to engineering.
 */
public final class Projection {

    public static final String CONSUMER = "analytics";

    private static final BigDecimal MONTH_DAYS = new BigDecimal("30");

    private Projection() {}

    /** Monthly-normalised value of a charge billed every intervalDays. */
    static BigDecimal monthly(BigDecimal amount, int intervalDays) {
        if (intervalDays <= 0) return BigDecimal.ZERO;
        return amount.multiply(MONTH_DAYS)
                .divide(new BigDecimal(intervalDays), 4, RoundingMode.HALF_UP);
    }

    /**
     * Apply one event, exactly once, in one transaction.
     *
     * Returns false when this delivery is a duplicate, which is not an error
     * and not a failure: it is the system working. The claim on handled_events
     * and the movement it guards commit together, so there is no window where
     * an event counts as handled without having been.
     */
    public static boolean apply(String eventKey, String payload) throws SQLException {
        try (Connection c = AnalyticsDb.open()) {
            c.setAutoCommit(false);
            try {
                boolean applied = apply(c, eventKey, payload);
                c.commit();
                return applied;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    static boolean apply(Connection c, String eventKey, String payload) throws SQLException {
        Instant businessAt = Instant.parse(Json.str(payload, "business_at"));
        if (!claim(c, eventKey, businessAt)) return false;   // seen it, and said so

        String type = Json.str(payload, "type");
        UUID subId = UUID.fromString(Json.str(payload, "subscription_id"));
        String tenant = Json.str(payload, "tenant");
        long customerId = Long.parseLong(Json.str(payload, "customer_id"));
        String variantId = Json.str(payload, "variant_id");
        BigDecimal amount = new BigDecimal(Json.str(payload, "amount"));
        int intervalDays = Integer.parseInt(Json.str(payload, "interval_days"));
        BigDecimal m = monthly(amount, intervalDays);

        switch (type) {
            case "subscription.started", "subscription.reactivated" -> {
                String kind = "subscription.started".equals(type) ? "new" : "reactivation";
                movement(c, eventKey, tenant, subId, customerId, variantId, kind, m, businessAt);
                upsertState(c, subId, tenant, customerId, variantId, "active", m, businessAt);
            }
            case "subscription.renewed" -> {
                // cash in, and the subscription is provably alive
                BigDecimal previous = storedMonthly(c, subId);
                bumpRenewal(c, subId, amount, m, businessAt);
                // MRR moves only if the PRICE moved
                if (previous != null && m.compareTo(previous) != 0) {
                    BigDecimal delta = m.subtract(previous);
                    movement(c, eventKey, tenant, subId, customerId, variantId,
                            delta.signum() > 0 ? "expansion" : "contraction", delta, businessAt);
                }
            }
            case "subscription.churned", "subscription.paused" -> {
                // the churn is worth what the subscription was worth to US,
                // not what this event happens to quote: a subscription that
                // expanded then churned takes the expanded amount with it
                BigDecimal stored = storedMonthly(c, subId);
                BigDecimal loss = (stored == null ? m : stored).negate();
                if (loss.signum() != 0) {
                    movement(c, eventKey, tenant, subId, customerId, variantId, "churn", loss, businessAt);
                }
                setStatus(c, subId, "subscription.paused".equals(type) ? "paused" : "canceled", businessAt);
            }
            default -> { /* an unknown type is recorded as handled and ignored, never fatal */ }
        }
        return true;
    }

    // ------------------------------------------------------------------ writes

    private static boolean claim(Connection c, String eventKey, Instant businessAt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO handled_events(event_key, consumer, business_at) VALUES (?,?,?) " +
                "ON CONFLICT (event_key, consumer) DO NOTHING")) {
            ps.setString(1, eventKey);
            ps.setString(2, CONSUMER);
            ps.setTimestamp(3, java.sql.Timestamp.from(businessAt));
            return ps.executeUpdate() == 1;
        }
    }

    private static void movement(Connection c, String eventKey, String tenant, UUID subId, long customerId,
                                 String variantId, String kind, BigDecimal amount, Instant businessAt)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO mrr_movements(tenant, subscription_id, customer_id, variant_id, kind,
                                          amount, provenance, event_key, business_at)
                VALUES (?,?,?,?,?,?, 'MEASURED', ?,?) ON CONFLICT (event_key) DO NOTHING""")) {
            ps.setString(1, tenant); ps.setObject(2, subId); ps.setLong(3, customerId);
            ps.setString(4, variantId); ps.setString(5, kind); ps.setBigDecimal(6, amount);
            ps.setString(7, eventKey); ps.setTimestamp(8, java.sql.Timestamp.from(businessAt));
            ps.executeUpdate();
        }
    }

    private static void upsertState(Connection c, UUID subId, String tenant, long customerId, String variantId,
                                    String status, BigDecimal monthly, Instant at) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO subscription_state(subscription_id, tenant, customer_id, variant_id, status,
                                               monthly_amount, started_at, last_event_at)
                VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT (subscription_id) DO UPDATE
                    SET status = EXCLUDED.status,
                        monthly_amount = EXCLUDED.monthly_amount,
                        last_event_at = EXCLUDED.last_event_at""")) {
            ps.setObject(1, subId); ps.setString(2, tenant); ps.setLong(3, customerId);
            ps.setString(4, variantId); ps.setString(5, status); ps.setBigDecimal(6, monthly);
            ps.setTimestamp(7, java.sql.Timestamp.from(at));
            ps.setTimestamp(8, java.sql.Timestamp.from(at));
            ps.executeUpdate();
        }
    }

    private static void bumpRenewal(Connection c, UUID subId, BigDecimal cash, BigDecimal monthly, Instant at)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE subscription_state
                   SET renewals = renewals + 1, collected = collected + ?,
                       monthly_amount = ?, status = 'active', last_event_at = ?
                 WHERE subscription_id = ?""")) {
            ps.setBigDecimal(1, cash); ps.setBigDecimal(2, monthly);
            ps.setTimestamp(3, java.sql.Timestamp.from(at)); ps.setObject(4, subId);
            ps.executeUpdate();
        }
    }

    private static void setStatus(Connection c, UUID subId, String status, Instant at) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE subscription_state SET status = ?, monthly_amount = 0, last_event_at = ? WHERE subscription_id = ?")) {
            ps.setString(1, status); ps.setTimestamp(2, java.sql.Timestamp.from(at)); ps.setObject(3, subId);
            ps.executeUpdate();
        }
    }

    private static BigDecimal storedMonthly(Connection c, UUID subId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT monthly_amount FROM subscription_state WHERE subscription_id = ?")) {
            ps.setObject(1, subId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getBigDecimal(1) : null; }
        }
    }

    // ------------------------------------------------------------------ reads

    /** Current MRR is the SUM of everything that ever moved it. Never a stored total. */
    public static BigDecimal mrr(String tenant) throws SQLException {
        try (Connection c = AnalyticsDb.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COALESCE(SUM(amount), 0) FROM mrr_movements WHERE tenant = ?")) {
            ps.setString(1, tenant);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getBigDecimal(1); }
        }
    }

    /** The same number the hard way, off the projection. If these two disagree,
     *  the projection has drifted and the movements are the ones to believe. */
    public static BigDecimal mrrFromState(String tenant) throws SQLException {
        try (Connection c = AnalyticsDb.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COALESCE(SUM(monthly_amount), 0) FROM subscription_state " +
                     "WHERE tenant = ? AND status = 'active'")) {
            ps.setString(1, tenant);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getBigDecimal(1); }
        }
    }
}
