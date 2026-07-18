package dev.minimart.commerce;

import dev.minimart.core.Db;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * THE SUBSCRIPTION BILLING ENGINE.
 *
 * One design rule keeps this small: A RENEWAL IS AN ORDER. Every period runs
 * through the same checkout a browsing customer uses, with an order id derived
 * from (subscription, period), so the reservation, the authorisation, the
 * capture, the compensation and every idempotency gate are all reused as they
 * are. Nothing about recurring billing needed its own money path.
 *
 * Two gates make a double-fire harmless:
 *   the invoice UNIQUE(subscription_id, period_index) claims the period, and
 *   the derived order id makes the underlying checkout idempotent anyway.
 *
 * Dunning is a bounded retry ladder in BUSINESS time, so a compressed run can
 * watch a month of failed retries pass in milliseconds.
 */
public final class Billing {

    /** How long to wait before each retry of a failed payment, then give up. */
    static final int[] DUNNING_BACKOFF_DAYS = {1, 3, 7};

    public record Report(int renewed, int failed, int recovered, int givenUp) {}

    private Billing() {}

    private static UUID derive(String s) { return UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8)); }

    /** Start a subscription and bill period 0 immediately. */
    public static UUID subscribe(String tenant, long customerId, String variantId, String location,
                                 int intervalDays, Instant businessAt) throws SQLException {
        UUID id = derive("sub:" + tenant + ':' + customerId + ':' + variantId);
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO subscriptions(id, tenant, customer_id, variant_id, location, status,
                                               interval_days, period_index, next_renewal_at, business_at)
                     VALUES (?,?,?,?,?, 'active', ?, 0, ?, ?) ON CONFLICT (id) DO NOTHING""")) {
            ps.setObject(1, id); ps.setString(2, tenant); ps.setLong(3, customerId);
            ps.setString(4, variantId); ps.setString(5, location); ps.setInt(6, intervalDays);
            ps.setTimestamp(7, java.sql.Timestamp.from(businessAt));   // due now: bill period 0
            ps.setTimestamp(8, java.sql.Timestamp.from(businessAt));
            ps.executeUpdate();
        }
        return id;
    }

    /**
     * One deterministic pass: bill every subscription whose period is due.
     * Called at a tick boundary by the simulation, or on a timer in production.
     */
    public static Report renewOnce(Instant now, int limit) throws SQLException {
        record Due(UUID id, String tenant, long customerId, String variantId, String location,
                   int periodIndex, int intervalDays, String status, boolean cancelAtEnd) {}
        List<Due> due = new ArrayList<>();
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id, tenant, customer_id, variant_id, location, period_index, interval_days,
                            status, cancel_at_period_end
                     FROM subscriptions
                     WHERE status IN ('active','past_due') AND next_renewal_at <= ?
                     ORDER BY next_renewal_at LIMIT ?""")) {
            ps.setTimestamp(1, java.sql.Timestamp.from(now));
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) due.add(new Due((UUID) rs.getObject(1), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getString(5), rs.getInt(6), rs.getInt(7),
                        rs.getString(8), rs.getBoolean(9)));
            }
        }

        int renewed = 0, failed = 0, recovered = 0, givenUp = 0;
        for (Due d : due) {
            if (d.cancelAtEnd()) { setStatus(d.id(), "canceled", now); continue; }

            // period_index is the last period SUCCESSFULLY billed, so the period
            // being attempted is always the next one. A failed attempt must not
            // move it, or every retry would bill a different period and the
            // attempt counter would never accumulate.
            boolean retrying = "past_due".equals(d.status());
            int period = d.periodIndex() + 1;

            // claim the period. A second firing of this pass finds the row taken.
            UUID invoiceId = derive("inv:" + d.id() + ':' + period);
            UUID orderId = derive("renew:" + d.id() + ':' + period);
            if (!claimPeriod(invoiceId, d.id(), period, orderId, now) && !retrying) continue;

            Checkout.Result r = Checkout.place(orderId, d.tenant(), d.customerId(),
                    d.variantId(), d.location(), 1, now);

            if (r instanceof Checkout.Placed p) {
                Checkout.ship(orderId, now);                       // a renewal ships immediately
                markInvoice(invoiceId, "paid", p.amount(), now);
                advance(d.id(), period, now.plus(Duration.ofDays(d.intervalDays())), "active", now);
                if (retrying) { recordDunning(d.id(), period, attemptsFor(d.id(), period) + 1, "recovered", now); recovered++; }
                renewed++;
            } else {
                int attempt = attemptsFor(d.id(), period) + 1;
                markInvoice(invoiceId, "failed", BigDecimal.ZERO, now);
                if (attempt > DUNNING_BACKOFF_DAYS.length) {
                    // out of retries: this is involuntary churn
                    recordDunning(d.id(), period, attempt, "given_up", now);
                    setStatus(d.id(), "canceled", now);
                    givenUp++;
                } else {
                    recordDunning(d.id(), period, attempt, "retry_scheduled", now);
                    // period_index deliberately unchanged: the NEXT pass retries
                    // this same period, and finds its attempt history waiting.
                    advance(d.id(), d.periodIndex(), now.plus(Duration.ofDays(DUNNING_BACKOFF_DAYS[attempt - 1])),
                            "past_due", now);
                    failed++;
                }
            }
        }
        return new Report(renewed, failed, recovered, givenUp);
    }

    public static void cancel(UUID subscriptionId, boolean atPeriodEnd, Instant now) throws SQLException {
        if (atPeriodEnd) {
            try (Connection c = Db.open();
                 PreparedStatement ps = c.prepareStatement(
                         "UPDATE subscriptions SET cancel_at_period_end = true WHERE id = ?")) {
                ps.setObject(1, subscriptionId);
                ps.executeUpdate();
            }
        } else {
            setStatus(subscriptionId, "canceled", now);
        }
    }

    public static void pause(UUID subscriptionId, Instant now) throws SQLException { setStatus(subscriptionId, "paused", now); }

    public static void resume(UUID subscriptionId, Instant nextRenewalAt, Instant now) throws SQLException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE subscriptions SET status = 'active', next_renewal_at = ? WHERE id = ?")) {
            ps.setTimestamp(1, java.sql.Timestamp.from(nextRenewalAt));
            ps.setObject(2, subscriptionId);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------- internals

    private static boolean claimPeriod(UUID invoiceId, UUID subId, int period, UUID orderId, Instant now)
            throws SQLException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO invoices(id, subscription_id, period_index, order_id, amount, status, business_at)
                     VALUES (?,?,?,?, 0, 'pending', ?) ON CONFLICT (subscription_id, period_index) DO NOTHING""")) {
            ps.setObject(1, invoiceId); ps.setObject(2, subId); ps.setInt(3, period);
            ps.setObject(4, orderId); ps.setTimestamp(5, java.sql.Timestamp.from(now));
            return ps.executeUpdate() == 1;
        }
    }

    private static void markInvoice(UUID invoiceId, String status, BigDecimal amount, Instant now) throws SQLException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE invoices SET status = ?, amount = ?, business_at = ? WHERE id = ?")) {
            ps.setString(1, status); ps.setBigDecimal(2, amount);
            ps.setTimestamp(3, java.sql.Timestamp.from(now)); ps.setObject(4, invoiceId);
            ps.executeUpdate();
        }
    }

    private static void advance(UUID subId, int periodIndex, Instant nextAt, String status, Instant now)
            throws SQLException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE subscriptions SET period_index = ?, next_renewal_at = ?, status = ? WHERE id = ?")) {
            ps.setInt(1, periodIndex); ps.setTimestamp(2, java.sql.Timestamp.from(nextAt));
            ps.setString(3, status); ps.setObject(4, subId);
            ps.executeUpdate();
        }
    }

    private static void setStatus(UUID subId, String status, Instant now) throws SQLException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("UPDATE subscriptions SET status = ? WHERE id = ?")) {
            ps.setString(1, status); ps.setObject(2, subId);
            ps.executeUpdate();
        }
    }

    private static int attemptsFor(UUID subId, int period) throws SQLException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM dunning_attempts WHERE subscription_id = ? AND period_index = ?")) {
            ps.setObject(1, subId); ps.setInt(2, period);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    private static void recordDunning(UUID subId, int period, int attempt, String outcome, Instant now)
            throws SQLException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO dunning_attempts(subscription_id, period_index, attempt, outcome, business_at)
                     VALUES (?,?,?,?,?)""")) {
            ps.setObject(1, subId); ps.setInt(2, period); ps.setInt(3, attempt);
            ps.setString(4, outcome); ps.setTimestamp(5, java.sql.Timestamp.from(now));
            ps.executeUpdate();
        }
    }
}
