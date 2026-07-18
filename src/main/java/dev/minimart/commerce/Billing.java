package dev.minimart.commerce;

import dev.minimart.core.Db;
import dev.minimart.core.Json;
import dev.minimart.core.Outbox;

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

    /** Subscription lifecycle, on its own topic. Analytics consumes this and
     *  never touches minimart's database, so the payload must carry everything
     *  a subscriber could need. A consumer that has to call back to the
     *  producer's tables is not a separate service, it is a distributed join. */
    public static final String TOPIC = "minimart.subscriptions.v1";

    /** How long to wait before each retry of a failed payment, then give up. */
    static final int[] DUNNING_BACKOFF_DAYS = {1, 3, 7};

    /** skipped counts periods this pass could not even attempt, because
     *  something on OUR side was wrong. It is reported separately from failed
     *  on purpose: failed means the customer's payment did not go through,
     *  skipped means we did not manage to ask. Collapsing the two would make an
     *  outage look like a collections problem, and the ladder would punish
     *  customers for it. */
    public record Report(int renewed, int failed, int recovered, int givenUp, int skipped) {}

    /**
     * What subscribe did, said by the code that actually did it.
     *
     * The API used to answer "was anything created" by taking COUNT(*) of the
     * whole table before and after. That is wrong twice over: it is a race,
     * because another customer subscribing in between makes an idempotent no-op
     * report created, and it is two unpredicated scans of a growing table on
     * every call. Only the INSERT knows the answer, so the INSERT reports it.
     */
    public record Subscription(UUID id, String status, boolean created) {}

    private Billing() {}

    private static UUID derive(String s) { return UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8)); }

    /** What the most recent subscribe on THIS thread did. A thread local rather
     *  than a changed return type, so the many existing callers that only want
     *  the id are untouched, and the one caller that has to tell a creation from
     *  a no-op can ask. Set inside the transaction that decided it. */
    private static final ThreadLocal<Subscription> lastResult = new ThreadLocal<>();

    /** The outcome of the last subscribe on this thread, or null. */
    public static Subscription lastSubscribeResult() { return lastResult.get(); }

    /** Start a subscription and bill period 0 immediately.
     *
     *  The id is derived so a retried subscribe is idempotent, but derived
     *  from a GENERATION as well, so a customer who cancelled can come back.
     *  The first version keyed on (tenant, customer, variant) alone with
     *  ON CONFLICT DO NOTHING, which meant a cancelled customer's resubscribe
     *  silently did nothing and handed back the dead subscription. */
    public static UUID subscribe(String tenant, long customerId, String variantId, String location,
                                 int intervalDays, Instant businessAt) throws SQLException {
        try (Connection c = Db.open()) {
            c.setAutoCommit(false);
            try {
                // a live subscription for this product is returned as-is: idempotent
                try (PreparedStatement ps = c.prepareStatement("""
                        SELECT id, status FROM subscriptions
                        WHERE tenant = ? AND customer_id = ? AND variant_id = ?
                          AND status IN ('active','past_due','paused')""")) {
                    ps.setString(1, tenant); ps.setLong(2, customerId); ps.setString(3, variantId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            UUID live = (UUID) rs.getObject(1);
                            String status = rs.getString(2);
                            c.rollback();
                            // nothing was created, and the status is the one
                            // actually held, which may well be past_due
                            lastResult.set(new Subscription(live, status, false));
                            return live;
                        }
                    }
                }
                // dead ones become earlier generations, so the new id is genuinely new
                int generation;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT COUNT(*) FROM subscriptions WHERE tenant = ? AND customer_id = ? AND variant_id = ?")) {
                    ps.setString(1, tenant); ps.setLong(2, customerId); ps.setString(3, variantId);
                    try (ResultSet rs = ps.executeQuery()) { rs.next(); generation = rs.getInt(1); }
                }
                UUID id = derive("sub:" + tenant + ':' + customerId + ':' + variantId + ':' + generation);
                int inserted;
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO subscriptions(id, tenant, customer_id, variant_id, location, status,
                                                  interval_days, period_index, next_renewal_at, business_at)
                        VALUES (?,?,?,?,?, 'active', ?, 0, ?, ?) ON CONFLICT (id) DO NOTHING""")) {
                    ps.setObject(1, id); ps.setString(2, tenant); ps.setLong(3, customerId);
                    ps.setString(4, variantId); ps.setString(5, location); ps.setInt(6, intervalDays);
                    ps.setTimestamp(7, java.sql.Timestamp.from(businessAt));
                    ps.setTimestamp(8, java.sql.Timestamp.from(businessAt));
                    // the row count IS the answer to "did this create anything",
                    // taken by the statement that would know
                    inserted = ps.executeUpdate();
                }
                // SAME TRANSACTION as the subscription row. A started event for a
                // subscription that rolled back would be a lie nobody could retract.
                emit(c, id, generation == 0 ? "subscription.started" : "subscription.reactivated",
                        tenant, customerId, variantId, priceOf(c, variantId), intervalDays,
                        generation, businessAt);
                c.commit();
                lastResult.set(new Subscription(id, "active", inserted == 1));
                return id;
            } catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
        }
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

        int renewed = 0, failed = 0, recovered = 0, givenUp = 0, skipped = 0;
        for (Due d : due) {
            // asked to stop at the end of the paid period, and the end is now
            if (d.cancelAtEnd()) {
                try (Connection c = Db.open()) {
                    c.setAutoCommit(false);
                    try {
                        emitFor(c, d.id(), "subscription.churned", now);
                        setStatus(c, d.id(), "canceled", now);
                        c.commit();
                    } catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
                }
                continue;
            }

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

            // EACH SUBSCRIPTION IS ITS OWN FAILURE DOMAIN.
            //
            // Anything unexpected below is a fault on our side, not the
            // customer's: a product never stocked at their location, a
            // processor that is down, a connection that could not be had. It
            // must cost this one renewal and nothing else. Without this, a
            // single unfulfillable row ends the pass and every customer queued
            // behind it goes unbilled, which is how one bad record becomes a
            // day of lost revenue.
            try {
            Checkout.Result r = Checkout.place(orderId, d.tenant(), d.customerId(),
                    d.variantId(), d.location(), 1, now);

            // AUTHORISED IS NOT CAPTURED. The first version threw this boolean
            // away and marked the invoice paid regardless, so a capture that
            // never landed still read as revenue.
            boolean captured = (r instanceof Checkout.Placed) && Checkout.ship(orderId, now);

            // authorised but not captured: give the goods and the hold back
            // before treating the period as failed, or the customer keeps a
            // reservation and an authorisation for something never delivered
            if (r instanceof Checkout.Placed && !captured) Checkout.cancel(orderId, now);

            // ONE TRANSACTION for the outcome. The first version spread the
            // invoice, the advance and the dunning row over five connections,
            // so a crash between them left a period claimed and never advanced.
            // The remote calls above cannot join it, nothing makes an HTTP call
            // atomic with a commit, but everything LOCAL now moves together.
            try (Connection c = Db.open()) {
                c.setAutoCommit(false);
                try {
                    if (r instanceof Checkout.Placed p && captured) {
                        markInvoice(c, invoiceId, "paid", p.amount(), now);
                        advance(c, d.id(), period, now.plus(Duration.ofDays(d.intervalDays())), "active", now);
                        if (retrying) {
                            recordDunning(c, d.id(), period, attemptsFor(c, d.id(), period) + 1, "recovered", now);
                            recovered++;
                        }
                        emit(c, d.id(), "subscription.renewed", d.tenant(), d.customerId(), d.variantId(),
                                p.amount(), d.intervalDays(), period, now);
                        renewed++;
                    } else {
                        int attempt = attemptsFor(c, d.id(), period) + 1;
                        markInvoice(c, invoiceId, "failed", BigDecimal.ZERO, now);
                        if (attempt > DUNNING_BACKOFF_DAYS.length) {
                            // out of retries: this is involuntary churn
                            recordDunning(c, d.id(), period, attempt, "given_up", now);
                            setStatus(c, d.id(), "canceled", now);
                            emit(c, d.id(), "subscription.churned", d.tenant(), d.customerId(), d.variantId(),
                                    priceOf(c, d.variantId()), d.intervalDays(), period, now);
                            givenUp++;
                        } else {
                            recordDunning(c, d.id(), period, attempt, "retry_scheduled", now);
                            // period_index deliberately unchanged: the NEXT pass retries
                            // this same period, and finds its attempt history waiting.
                            advance(c, d.id(), d.periodIndex(),
                                    now.plus(Duration.ofDays(DUNNING_BACKOFF_DAYS[attempt - 1])), "past_due", now);
                            failed++;
                        }
                    }
                    c.commit();
                } catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
            }
            } catch (SQLException | RuntimeException e) {
                // Release the claim. The invoice row was inserted to reserve
                // this period, and leaving it behind would make the next pass
                // see the period as already taken and skip it forever, which
                // turns a transient fault into a subscription that silently
                // never bills again.
                releaseClaim(invoiceId);
                // NO dunning attempt and NO failed invoice. The customer's card
                // was never asked, so nothing about this is their failure, and
                // pushing them into the ladder would end with them cancelled
                // for a warehouse mistake. The subscription stays due, and the
                // next pass tries again once the cause is fixed.
                System.err.println("billing: skipped subscription " + d.id() + " period " + period
                        + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
                skipped++;
            }
        }
        return new Report(renewed, failed, recovered, givenUp, skipped);
    }

    public static void cancel(UUID subscriptionId, boolean atPeriodEnd, Instant now) throws SQLException {
        try (Connection c = Db.open()) {
            c.setAutoCommit(false);
            try {
                if (atPeriodEnd) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE subscriptions SET cancel_at_period_end = true WHERE id = ?")) {
                        ps.setObject(1, subscriptionId);
                        ps.executeUpdate();
                    }
                } else {
                    emitFor(c, subscriptionId, "subscription.churned", now);
                    setStatus(c, subscriptionId, "canceled", now);
                }
                c.commit();
            } catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
        }
    }

    public static void pause(UUID subscriptionId, Instant now) throws SQLException {
        try (Connection c = Db.open()) {
            c.setAutoCommit(false);
            try {
                emitFor(c, subscriptionId, "subscription.paused", now);
                setStatus(c, subscriptionId, "paused", now);
                c.commit();
            } catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
        }
    }

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

    /** Undo a period claim that could not be attempted. Deleting is safe
     *  precisely because the row is still pending: a claim that never became
     *  an outcome carries no information worth keeping, and keeping it would
     *  block the period forever. */
    private static void releaseClaim(UUID invoiceId) {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM invoices WHERE id = ? AND status = 'pending'")) {
            ps.setObject(1, invoiceId);
            ps.executeUpdate();
        } catch (SQLException e) {
            // the database is the thing that is unwell; the next pass will find
            // the pending row and this is not the moment to make it worse
            System.err.println("billing: could not release claim " + invoiceId + ": " + e.getMessage());
        }
    }

    private static void markInvoice(Connection c, UUID invoiceId, String status, BigDecimal amount, Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                     "UPDATE invoices SET status = ?, amount = ?, business_at = ? WHERE id = ?")) {
            ps.setString(1, status); ps.setBigDecimal(2, amount);
            ps.setTimestamp(3, java.sql.Timestamp.from(now)); ps.setObject(4, invoiceId);
            ps.executeUpdate();
        }
    }

    private static void advance(Connection c, UUID subId, int periodIndex, Instant nextAt, String status, Instant now)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                     "UPDATE subscriptions SET period_index = ?, next_renewal_at = ?, status = ? WHERE id = ?")) {
            ps.setInt(1, periodIndex); ps.setTimestamp(2, java.sql.Timestamp.from(nextAt));
            ps.setString(3, status); ps.setObject(4, subId);
            ps.executeUpdate();
        }
    }

    private static void setStatus(Connection c, UUID subId, String status, Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE subscriptions SET status = ? WHERE id = ?")) {
            ps.setString(1, status); ps.setObject(2, subId);
            ps.executeUpdate();
        }
    }

    private static int attemptsFor(Connection c, UUID subId, int period) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM dunning_attempts WHERE subscription_id = ? AND period_index = ?")) {
            ps.setObject(1, subId); ps.setInt(2, period);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    private static void recordDunning(Connection c, UUID subId, int period, int attempt, String outcome, Instant now)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO dunning_attempts(subscription_id, period_index, attempt, outcome, business_at)
                     VALUES (?,?,?,?,?)""")) {
            ps.setObject(1, subId); ps.setInt(2, period); ps.setInt(3, attempt);
            ps.setString(4, outcome); ps.setTimestamp(5, java.sql.Timestamp.from(now));
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------ the contract

    private static BigDecimal priceOf(Connection c, String variantId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT price FROM variants WHERE id = ?")) {
            ps.setString(1, variantId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO; }
        }
    }

    /** Read the subscription and emit an event about it, in the caller's transaction. */
    private static void emitFor(Connection c, UUID subId, String type, Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT tenant, customer_id, variant_id, interval_days, period_index FROM subscriptions WHERE id = ?")) {
            ps.setObject(1, subId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return;
                emit(c, subId, type, rs.getString(1), rs.getLong(2), rs.getString(3),
                        priceOf(c, rs.getString(3)), rs.getInt(4), rs.getInt(5), now);
            }
        }
    }

    /**
     * The event carries AMOUNT and INTERVAL, not just ids.
     *
     * Analytics lives in another database and cannot look a price up. If the
     * event forced it to, the two services would be joined through a table and
     * only pretending to be independent. Carrying the values also makes the
     * event a permanent record of what was true AT THE TIME, which is what a
     * revenue figure has to be: repricing a variant next year must not silently
     * rewrite last year's MRR.
     *
     * The sequence disambiguates repeats: a subscription can be started,
     * churned and started again, and each is a distinct event.
     */
    private static void emit(Connection c, UUID subId, String type, String tenant, long customerId,
                             String variantId, BigDecimal amount, int intervalDays, int sequence,
                             Instant businessAt) throws SQLException {
        String eventKey = type + ':' + subId + ':' + sequence;
        String payload = Json.obj(
                "type", type,
                "subscription_id", subId.toString(),
                "tenant", tenant,
                "customer_id", String.valueOf(customerId),
                "variant_id", variantId,
                "amount", amount.toPlainString(),
                "interval_days", String.valueOf(intervalDays),
                "sequence", String.valueOf(sequence),
                "business_at", businessAt.toString());
        // partitioned by SUBSCRIPTION, so one subscription's history can never
        // arrive out of order, however many partitions the topic grows to
        Outbox.append(c, TOPIC, eventKey, subId.toString(), payload, businessAt);
    }
}
