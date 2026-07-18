package dev.minianalytics;

import com.sun.net.httpserver.HttpServer;
import dev.minimart.commerce.Billing;
import dev.minimart.commerce.Checkout;
import dev.minimart.commerce.Orders;
import dev.minimart.core.Db;
import dev.minimart.core.Migrate;
import dev.minimart.core.OutboxRelay;
import dev.minipay.PayApi;
import dev.minipay.PayDb;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE ANALYTICS SERVICE · a third database, a consumer group, and one honest
 * revenue number.
 *
 * This is the first piece of the system that owns no goods and no money. It
 * only knows what it was told, which is exactly the position every reporting
 * service in a real company is in, and exactly why they are so often wrong.
 * The four lessons here are the four ways they go wrong: double counting a
 * replay, calling a renewal growth, quoting a number nobody can trace, and
 * being incomplete without knowing it.
 */
class AnalyticsLessonTest {

    static HttpServer pay;
    static final int PAY_PORT = 18150;
    static final String TENANT = "helix", LOC = "MAD";
    static final String MONTHLY = "v-recovery-30", YEARLY = "v-recovery-365";
    static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
    static final String KAFKA = System.getenv().getOrDefault("MINIMART_KAFKA", "localhost:9093");

    @BeforeAll
    static void boot() throws Exception {
        Migrate.bootstrap();
        PayDb.bootstrap();
        AnalyticsDb.bootstrap();
        pay = PayApi.start(PAY_PORT);
    }

    @AfterAll
    static void stop() { if (pay != null) pay.stop(0); }

    @BeforeEach
    void reset() throws Exception {
        Checkout.payBaseUrl = "http://localhost:" + PAY_PORT;
        Checkout.declineCustomerIds.clear();
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE outbox, handled_events, dunning_attempts, invoices, subscriptions, reservations, orders, entries, transactions, accounts, variants, tenants RESTART IDENTITY CASCADE");
            st.execute("INSERT INTO tenants(slug) VALUES ('" + TENANT + "')");
            st.execute("INSERT INTO variants(id, tenant, title, price) VALUES ('" + MONTHLY + "','" + TENANT + "','Recovery Stack', 60.00)");
            st.execute("INSERT INTO variants(id, tenant, title, price) VALUES ('" + YEARLY + "','" + TENANT + "','Recovery Stack Annual', 600.00)");
        }
        try (Connection c = PayDb.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE idempotency_keys, payment_intents, entries, transactions, accounts RESTART IDENTITY CASCADE");
        }
        try (Connection c = AnalyticsDb.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE handled_events, mrr_movements, subscription_state RESTART IDENTITY CASCADE");
        }
        Orders.receiveStock(TENANT, LOC, MONTHLY, 500, T0);
        Orders.receiveStock(TENANT, LOC, YEARLY, 500, T0);
    }

    /**
     * LESSON 1 · THE SAME EVENT, DELIVERED FIVE TIMES, COUNTS ONCE.
     *
     * At-least-once is not a compromise the broker apologises for, it is the
     * only delivery guarantee worth having, because the alternative loses
     * events silently. The price is that the CONSUMER must be idempotent, and
     * the claim has to live in the same transaction as the effect it guards.
     * Here the same subscription event is applied five times deliberately.
     */
    @Test
    void lesson1_a_replayed_event_is_counted_exactly_once() throws Exception {
        Billing.subscribe(TENANT, 3001L, MONTHLY, LOC, 30, T0);
        var events = pendingEvents(Billing.TOPIC);
        assertEquals(1, events.size(), "one subscribe, one event");

        int applied = 0;
        for (int i = 0; i < 5; i++) {
            if (Projection.apply(events.get(0).key(), events.get(0).payload())) applied++;
        }

        assertEquals(1, applied, "five deliveries, one application");
        assertEquals(0, new BigDecimal("60.0000").compareTo(Projection.mrr(TENANT)),
                "MRR counted once, not five times");
        assertEquals(1, movementCount(), "one movement row, guarded by the event key");
        System.out.println("lesson 1: 5 deliveries -> 1 movement, MRR " + Projection.mrr(TENANT));
    }

    /**
     * LESSON 2 · A RENEWAL IS CASH, NOT GROWTH.
     *
     * The most common bug in a revenue dashboard, and the one that flatters
     * hardest. Three months of renewals do not triple MRR, they prove it. Cash
     * collected and MRR are different questions and the ledger answers both
     * without confusing them.
     *
     * The second half is the case that catches out anyone who "fixes" the
     * first by ignoring renewals entirely: when the PRICE changes on renewal,
     * MRR really does move, by the difference and only the difference.
     */
    @Test
    void lesson2_renewals_are_revenue_but_not_new_mrr() throws Exception {
        Billing.subscribe(TENANT, 3002L, MONTHLY, LOC, 30, T0);
        applyAll();
        BigDecimal afterStart = Projection.mrr(TENANT);

        // three months pass, three renewals succeed
        for (int month = 1; month <= 3; month++) {
            Billing.renewOnce(T0.plus(Duration.ofDays(30L * month)), 50);
            applyAll();
        }

        assertEquals(0, afterStart.compareTo(Projection.mrr(TENANT)),
                "three renewals, and MRR is exactly where it started: it never was growth");
        assertEquals(3, renewalsOf(TENANT), "but all three renewals were observed");
        assertEquals(0, new BigDecimal("180.00").compareTo(collectedOf(TENANT)),
                "and the cash they brought in is counted separately, in full");

        // now the price rises: 60 -> 90, and THIS is growth
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("UPDATE variants SET price = 90.00 WHERE id = '" + MONTHLY + "'");
        }
        Billing.renewOnce(T0.plus(Duration.ofDays(120)), 50);
        applyAll();

        assertEquals(0, new BigDecimal("90.0000").compareTo(Projection.mrr(TENANT)),
                "MRR moved to the new price");
        assertEquals(1, movementCount("expansion"), "and it moved as ONE expansion of the difference");
        assertEquals(0, new BigDecimal("30.0000").compareTo(movementSum("expansion")),
                "the expansion is the delta, 30, not the whole new price");
        System.out.println("lesson 2: 3 renewals -> MRR flat at 60 and 180 collected; a reprice -> +30 expansion");
    }

    /**
     * LESSON 3 · EVERY NUMBER IS TRACEABLE, AND NORMALISED.
     *
     * MRR is stored as MOVEMENTS, never as a total, for the same reason the
     * money is stored as entries: a figure you can overwrite is a figure nobody
     * can audit. Two independent paths to the number must agree, and a yearly
     * plan must not be counted as twelve months of revenue in one month.
     */
    @Test
    void lesson3_mrr_is_a_traceable_sum_and_a_yearly_plan_is_normalised() throws Exception {
        Billing.subscribe(TENANT, 3003L, MONTHLY, LOC, 30, T0);    // 60 / month  -> 60
        Billing.subscribe(TENANT, 3004L, YEARLY, LOC, 365, T0);    // 600 / year  -> 49.3151
        applyAll();

        // 600 * 30 / 365 = 49.3151, NOT 600
        assertEquals(0, new BigDecimal("49.3151").compareTo(monthlyOf(3004L)),
                "a yearly plan contributes a month of itself, not a year");
        assertEquals(0, new BigDecimal("109.3151").compareTo(Projection.mrr(TENANT)),
                "the total mixes intervals without lying about either");

        // the same number, reached two different ways, from two different tables
        assertEquals(0, Audit.drift(TENANT).signum(),
                "the movement ledger and the projection agree exactly");

        // and the projection is disposable: rebuilt from the ledger, nothing moves
        BigDecimal before = Projection.mrrFromState(TENANT);
        Audit.rebuildProjection();
        assertEquals(0, before.compareTo(Projection.mrrFromState(TENANT)),
                "rebuilding the read model from the ledger changes nothing, which is the proof");
        System.out.println("lesson 3: MRR " + Projection.mrr(TENANT) + " from movements, identical from state, and rebuildable");
    }

    /**
     * LESSON 4 · A GAP MUST BE DETECTABLE, NOT MERELY ABSENT.
     *
     * Lag is acceptable and normal. Silent loss is not, because an incomplete
     * dashboard is indistinguishable from a complete one and gets believed
     * either way. So completeness is a QUERY: the producer knows what it
     * published, the consumer knows what it handled, and the difference is
     * named. Here an event is deliberately withheld and the audit finds it.
     */
    @Test
    void lesson4_the_service_can_prove_what_it_never_received() throws Exception {
        Billing.subscribe(TENANT, 3005L, MONTHLY, LOC, 30, T0);
        Billing.subscribe(TENANT, 3006L, MONTHLY, LOC, 30, T0);
        Billing.subscribe(TENANT, 3007L, MONTHLY, LOC, 30, T0);

        var events = pendingEvents(Billing.TOPIC);
        assertEquals(3, events.size());
        List<String> published = events.stream().map(Pending::key).toList();

        // two arrive, one is lost in the post
        Projection.apply(events.get(0).key(), events.get(0).payload());
        Projection.apply(events.get(1).key(), events.get(1).payload());

        Audit.Completeness before = Audit.completeness(published);
        assertFalse(before.complete(), "the service does not claim to be complete when it is not");
        assertEquals(1, before.missing().size());
        assertEquals(events.get(2).key(), before.missing().get(0),
                "and it names exactly which event it never saw");

        // the relay redelivers, as at-least-once promises it eventually will
        Projection.apply(events.get(2).key(), events.get(2).payload());
        Audit.Completeness after = Audit.completeness(published);
        assertTrue(after.complete(), "and once it arrives, the gap closes");
        assertEquals(1.0, after.coverage());
        System.out.println("lesson 4: 2 of 3 handled -> coverage " + before.coverage() + ", missing named; then complete");
    }

    /**
     * LESSON 5 · THE WHOLE PATH, THROUGH A REAL BROKER.
     *
     * Everything above applies events directly, which tests the meaning. This
     * one tests the plumbing: a subscription in minimart's database, an outbox
     * row in the same commit, a relay that publishes only after the broker
     * acknowledges, a consumer group reading the topic, and a number in a third
     * database that no query could have reached across.
     */
    @Test
    void lesson5_end_to_end_over_kafka_into_a_separate_database() throws Exception {
        Billing.subscribe(TENANT, 3008L, MONTHLY, LOC, 30, T0);
        Billing.subscribe(TENANT, 3009L, YEARLY, LOC, 365, T0);

        OutboxRelay relay = new OutboxRelay(KAFKA);
        try {
            assertTrue(relay.publishPending(100) >= 2, "the relay shipped the subscription events");
        } finally {
            relay.close();
        }

        AnalyticsConsumer consumer = new AnalyticsConsumer(KAFKA, Billing.TOPIC);
        try {
            int applied = 0;
            long deadline = System.currentTimeMillis() + 20_000;
            while (applied < 2 && System.currentTimeMillis() < deadline) {
                applied += consumer.drainOnce(Duration.ofMillis(500));
            }
            assertEquals(2, applied, "both events crossed the broker and were applied once each");
        } finally {
            consumer.close();
        }

        assertEquals(0, new BigDecimal("109.3151").compareTo(Projection.mrr(TENANT)),
                "the revenue figure arrived in a database that cannot see minimart's tables");
        System.out.println("lesson 5: minimart -> outbox -> kafka -> analytics db, MRR " + Projection.mrr(TENANT));
    }

    // ------------------------------------------------------------------ helpers

    record Pending(String key, String payload) {}

    /** Read straight out of minimart's outbox, standing in for the broker in the
     *  lessons that are about MEANING rather than about delivery. */
    private static List<Pending> pendingEvents(String topic) throws Exception {
        List<Pending> out = new ArrayList<>();
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT event_key, payload FROM outbox WHERE topic = ? ORDER BY id")) {
            ps.setString(1, topic);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Pending(rs.getString(1), rs.getString(2)));
            }
        }
        return out;
    }

    private static void applyAll() throws Exception {
        for (Pending p : pendingEvents(Billing.TOPIC)) Projection.apply(p.key(), p.payload());
    }

    private static long movementCount() throws Exception { return one("SELECT COUNT(*) FROM mrr_movements"); }

    private static long movementCount(String kind) throws Exception {
        return one("SELECT COUNT(*) FROM mrr_movements WHERE kind = '" + kind + "'");
    }

    private static BigDecimal movementSum(String kind) throws Exception {
        return dec("SELECT COALESCE(SUM(amount),0) FROM mrr_movements WHERE kind = '" + kind + "'");
    }

    private static long renewalsOf(String tenant) throws Exception {
        return one("SELECT COALESCE(SUM(renewals),0) FROM subscription_state WHERE tenant = '" + tenant + "'");
    }

    private static BigDecimal collectedOf(String tenant) throws Exception {
        return dec("SELECT COALESCE(SUM(collected),0) FROM subscription_state WHERE tenant = '" + tenant + "'");
    }

    private static BigDecimal monthlyOf(long customerId) throws Exception {
        return dec("SELECT monthly_amount FROM subscription_state WHERE customer_id = " + customerId);
    }

    private static long one(String sql) throws Exception {
        try (Connection c = AnalyticsDb.open(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
    }

    private static BigDecimal dec(String sql) throws Exception {
        try (Connection c = AnalyticsDb.open(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getBigDecimal(1); }
    }
}
