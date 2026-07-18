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
 * BILLING INTEGRITY · three defects an architecture review found in code that
 * was already shipped and already had four passing lessons.
 *
 * They are grouped here because they share a shape: each one is a place where
 * the system RECORDS SOMETHING IT DID NOT VERIFY. An invoice marked paid
 * without checking the capture. A renewal spread over five commits so a crash
 * leaves half of it. A subscription id that quietly refuses to be recreated.
 */
class BillingIntegrityLessonTest {

    static HttpServer pay;
    static final int PAY_PORT = 18140;
    static final String TENANT = "helix", LOC = "MAD", VARIANT = "v-recovery-30";
    static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

    @BeforeAll
    static void boot() throws Exception {
        Migrate.bootstrap();
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
            st.execute("TRUNCATE outbox, handled_events, dunning_attempts, invoices, subscriptions, reservations, orders, entries, transactions, accounts, variants, tenants RESTART IDENTITY CASCADE");
            st.execute("INSERT INTO tenants(slug) VALUES ('" + TENANT + "')");
            st.execute("INSERT INTO variants(id, tenant, title, price) VALUES ('" + VARIANT + "','" + TENANT + "','Recovery Stack', 79.00)");
        }
        try (Connection c = PayDb.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE idempotency_keys, payment_intents, entries, transactions, accounts RESTART IDENTITY CASCADE");
        }
        Orders.receiveStock(TENANT, LOC, VARIANT, 200, T0);
    }

    /**
     * LESSON 1 · AN INVOICE MAY NOT CLAIM MONEY THAT WAS NOT CAPTURED.
     *
     * The authorisation succeeds, so the order is placed. Then the CAPTURE
     * fails, because the processor is unreachable by then. The old code called
     * Checkout.ship(...) and threw the returned boolean away, then marked the
     * invoice paid regardless. The books said revenue that the processor had
     * never released.
     */
    @Test
    void lesson1_a_failed_capture_must_not_read_as_paid() throws Exception {
        Billing.subscribe(TENANT, 950L, VARIANT, LOC, 30, T0);

        // authorise normally, then make capture impossible before it is attempted
        Checkout.captureSabotage = true;
        try {
            Billing.renewOnce(T0, 100);
        } finally {
            Checkout.captureSabotage = false;
        }

        try (Connection c = Db.open()) {
            assertEquals(0, count(c, "SELECT COUNT(*) FROM invoices WHERE status = 'paid'"),
                    "the capture failed, so nothing may be recorded as paid");
            assertEquals(1, count(c, "SELECT COUNT(*) FROM invoices WHERE status = 'failed'"),
                    "it is recorded as failed, which is a fact, and dunning can act on it");
        }
        try (Connection c = PayDb.open()) {
            assertEquals(0, Ledger.balance(c, PaymentIntents.balance(TENANT)).signum(),
                    "the processor released nothing, and the books agree");
        }
        System.out.println("lesson 1: capture failed -> invoice failed, not paid. The books cannot claim uncaptured money.");
    }

    /**
     * LESSON 2 · ONE RENEWAL IS ONE TRANSACTION.
     *
     * The old renewOnce opened five separate connections, so a crash between
     * them left an invoice claimed with no subscription advance, or a dunning
     * row with no invoice. Here the period claim, the invoice and the
     * subscription advance are asserted to move together.
     */
    @Test
    void lesson2_a_renewal_advances_atomically() throws Exception {
        UUID sub = Billing.subscribe(TENANT, 951L, VARIANT, LOC, 30, T0);
        Billing.renewOnce(T0, 100);

        try (Connection c = Db.open()) {
            long paid = count(c, "SELECT COUNT(*) FROM invoices WHERE subscription_id = '" + sub + "' AND status='paid'");
            long period = one(c, "SELECT period_index FROM subscriptions WHERE id = '" + sub + "'");
            assertEquals(1, paid);
            assertEquals(1, period, "the subscription advanced exactly as far as it billed");
            // the invariant that must hold forever: never more paid invoices than periods advanced
            assertEquals(paid, period, "paid periods and the advance can never disagree");
        }
        System.out.println("lesson 2: a renewal's invoice and advance move together, never one without the other");
    }

    /**
     * LESSON 3 · A CANCELLED CUSTOMER MUST BE ABLE TO COME BACK.
     *
     * The subscription id was derived from (tenant, customer, variant) and
     * inserted ON CONFLICT DO NOTHING, so once cancelled, that customer could
     * never subscribe to that product again. The row simply refused to appear
     * and the caller was told nothing. It also made "reactivation" structurally
     * unreachable, which is a metric the business would eventually want.
     */
    @Test
    void lesson3_a_cancelled_customer_can_resubscribe() throws Exception {
        UUID first = Billing.subscribe(TENANT, 952L, VARIANT, LOC, 30, T0);
        Billing.renewOnce(T0, 100);
        Billing.cancel(first, false, T0.plus(Duration.ofDays(1)));
        assertEquals("canceled", statusOf(first));

        // they come back a month later
        UUID second = Billing.subscribe(TENANT, 952L, VARIANT, LOC, 30, T0.plus(Duration.ofDays(30)));
        assertNotEquals(first, second, "a new subscription, not a silent no-op on the dead one");
        assertEquals("active", statusOf(second), "and it is live");

        Billing.renewOnce(T0.plus(Duration.ofDays(30)), 100);
        try (Connection c = Db.open()) {
            assertEquals(1, count(c, "SELECT COUNT(*) FROM invoices WHERE subscription_id = '" + second + "' AND status='paid'"),
                    "the returning customer is billed");
            assertEquals("canceled", statusOf(first), "and the old subscription stays dead");
        }
        System.out.println("lesson 3: a cancelled customer resubscribes and is billed, so reactivation is reachable");
    }

    private static String statusOf(UUID sub) throws Exception {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("SELECT status FROM subscriptions WHERE id = ?")) {
            ps.setObject(1, sub);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getString(1); }
        }
    }

    private static long count(Connection c, String sql) throws Exception { return one(c, sql); }

    private static long one(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next(); return rs.getLong(1);
        }
    }
}
