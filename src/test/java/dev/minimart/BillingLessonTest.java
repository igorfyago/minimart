package dev.minimart;

import com.sun.net.httpserver.HttpServer;
import dev.minimart.commerce.Billing;
import dev.minimart.commerce.Checkout;
import dev.minimart.commerce.Orders;
import dev.minimart.core.Db;
import dev.minimart.core.Ledger;
import dev.minimart.core.Migrate;
import dev.minipay.PayApi;
import dev.minipay.PayDb;
import dev.minipay.PaymentIntents;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RECURRING BILLING.
 *
 * A renewal is an order, so these lessons are really about two things: that a
 * period can be billed exactly once no matter how often the scheduler fires,
 * and that a failing card walks a bounded retry ladder in business time rather
 * than either giving up instantly or retrying forever.
 */
class BillingLessonTest {

    static HttpServer pay;
    static final int PAY_PORT = 18130;
    static final String TENANT = "helix", LOC = "MAD", VARIANT = "v-recovery-30";
    static final Instant T0 = Instant.parse("2026-06-01T00:00:00Z");

    @BeforeAll
    static void boot() throws Exception {
        Migrate.run();
        PayDb.bootstrap();
        pay = PayApi.start(PAY_PORT);
    }

    @AfterAll
    static void stop() { if (pay != null) pay.stop(0); }

    @BeforeEach
    void reset() throws Exception {
        Checkout.payBaseUrl = "http://localhost:" + PAY_PORT;
        Checkout.declineCustomerIds.clear();
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE dunning_attempts, invoices, subscriptions, reservations, orders, entries, transactions, accounts, variants, tenants RESTART IDENTITY CASCADE");
            st.execute("INSERT INTO tenants(slug) VALUES ('" + TENANT + "')");
            st.execute("INSERT INTO variants(id, tenant, title, price) VALUES ('" + VARIANT + "','" + TENANT + "','Recovery Stack', 79.00)");
        }
        try (Connection c = PayDb.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE idempotency_keys, payment_intents, entries, transactions, accounts RESTART IDENTITY CASCADE");
        }
        Orders.receiveStock(TENANT, LOC, VARIANT, 500, T0);
    }

    /** LESSON 1 · six months of billing pass in milliseconds of wall clock. */
    @Test
    void lesson1_a_subscription_renews_on_the_business_clock() throws Exception {
        Billing.subscribe(TENANT, 900L, VARIANT, LOC, 30, T0);

        // one pass per day for 180 compressed days
        int renewed = 0;
        for (int day = 0; day <= 180; day++) {
            renewed += Billing.renewOnce(T0.plus(Duration.ofDays(day)), 100).renewed();
        }

        assertEquals(7, renewed, "period 0 plus six monthly renewals in 180 days");
        try (Connection c = Db.open()) {
            assertEquals(7, count(c, "SELECT COUNT(*) FROM invoices WHERE status = 'paid'"));
            assertEquals(7, count(c, "SELECT COUNT(*) FROM orders"), "every renewal really is an order");
        }
        try (Connection c = PayDb.open()) {
            assertEquals(new BigDecimal("553.00"),
                    Ledger.balance(c, PaymentIntents.balance(TENANT)).stripTrailingZeros().setScale(2),
                    "7 x 79.00 captured at the processor");
            assertTrue(Ledger.sumZeroViolations(c).isEmpty());
        }
        System.out.println("lesson 1: 180 compressed days · " + renewed + " billing periods, 553.00 captured");
    }

    /** LESSON 2 · the scheduler double-fires. The customer is billed once. */
    @Test
    void lesson2_a_period_can_only_be_billed_once() throws Exception {
        Billing.subscribe(TENANT, 901L, VARIANT, LOC, 30, T0);

        // the same due moment processed five times, as a crashing scheduler would
        for (int i = 0; i < 5; i++) Billing.renewOnce(T0, 100);

        try (Connection c = Db.open()) {
            assertEquals(1, count(c, "SELECT COUNT(*) FROM invoices"), "one invoice for period 0");
            assertEquals(1, count(c, "SELECT COUNT(*) FROM orders"), "one order");
        }
        try (Connection c = PayDb.open()) {
            assertEquals(new BigDecimal("79.00"),
                    Ledger.balance(c, PaymentIntents.balance(TENANT)).stripTrailingZeros().setScale(2),
                    "charged once, not five times");
        }
        System.out.println("lesson 2: five firings of the same due period · one invoice, one charge");
    }

    /** LESSON 3 · a failing card walks the retry ladder, then churns involuntarily. */
    @Test
    void lesson3_dunning_retries_then_gives_up() throws Exception {
        // a customer whose instrument always declines, the processor's test path
        Checkout.declineCustomerIds.add(902L);
        UUID sub = Billing.subscribe(TENANT, 902L, VARIANT, LOC, 30, T0);

        var first = Billing.renewOnce(T0, 100);
        assertEquals(1, first.failed(), "attempt 1 fails and schedules a retry");
        assertEquals("past_due", statusOf(sub));

        // walk the ladder: +1 day, +3 days, +7 days, then give up
        var r2 = Billing.renewOnce(T0.plus(Duration.ofDays(1)), 100);
        var r3 = Billing.renewOnce(T0.plus(Duration.ofDays(4)), 100);
        var r4 = Billing.renewOnce(T0.plus(Duration.ofDays(11)), 100);

        assertEquals(1, r4.givenUp(), "after the ladder is exhausted, the subscription is cancelled");
        assertEquals("canceled", statusOf(sub));
        try (Connection c = Db.open()) {
            assertEquals(4, count(c, "SELECT COUNT(*) FROM dunning_attempts WHERE subscription_id = '" + sub + "'"));
            assertEquals(0, count(c, "SELECT COUNT(*) FROM invoices WHERE status = 'paid'"), "never paid");
            // and no stock was consumed by a payment that never succeeded
            assertEquals(500, Ledger.balance(c, Orders.onHand(LOC, VARIANT)).intValueExact());
        }
        System.out.println("lesson 3: declining card · 3 scheduled retries then involuntary churn, no stock consumed");
    }

    /** LESSON 4 · cancel at period end stops the next bill, not the current one. */
    @Test
    void lesson4_cancel_at_period_end() throws Exception {
        UUID sub = Billing.subscribe(TENANT, 903L, VARIANT, LOC, 30, T0);
        Billing.renewOnce(T0, 100);                                  // period 0 billed
        Billing.cancel(sub, true, T0.plus(Duration.ofDays(1)));      // "cancel when this period ends"

        Billing.renewOnce(T0.plus(Duration.ofDays(31)), 100);        // the next due date arrives

        assertEquals("canceled", statusOf(sub));
        try (Connection c = Db.open()) {
            assertEquals(1, count(c, "SELECT COUNT(*) FROM invoices"), "the paid period stands, the next never bills");
        }
        System.out.println("lesson 4: cancel at period end · current period kept, next period never billed");
    }



    private static String statusOf(UUID sub) throws Exception {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("SELECT status FROM subscriptions WHERE id = ?")) {
            ps.setObject(1, sub);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getString(1); }
        }
    }

    private static long count(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next(); return rs.getLong(1);
        }
    }
}
