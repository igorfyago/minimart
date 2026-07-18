package dev.minimart;

import com.sun.net.httpserver.HttpServer;
import dev.minimart.commerce.Billing;
import dev.minimart.commerce.Checkout;
import dev.minimart.commerce.Orders;
import dev.minimart.core.Db;
import dev.minimart.core.Migrate;
import dev.minipay.PayApi;
import dev.minipay.PayDb;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ONE BAD SUBSCRIPTION MUST NOT STOP EVERYONE ELSE BEING BILLED.
 *
 * Found in the running system, not in review. A customer had subscribed to a
 * product that was never stocked at their location, so when the renewal pass
 * reached them the ledger threw, the exception left renewOnce, and the whole
 * batch stopped. Every subscription queued behind that one went unbilled, and
 * the endpoint returned a 500 that said only "no such account".
 *
 * This is the difference between a system that degrades and one that falls
 * over. A batch job that processes N independent items has N independent
 * failure domains, and the moment one item can end the run, the blast radius of
 * any single bad row is the entire revenue of the business that day.
 *
 * The second half is the part that is easy to get wrong while fixing the first:
 * a failure the system caused must not be charged to the CUSTOMER. Wrapping the
 * loop in a catch that marks every error as a failed payment would quietly push
 * people into the dunning ladder and eventually cancel them, for an outage that
 * was never anything to do with them.
 */
class BillingResilienceLessonTest {

    static HttpServer pay;
    static final int PAY_PORT = 18160;
    static final String TENANT = "helix", LOC = "MAD";
    static final String STOCKED = "v-stocked-30", NEVER_STOCKED = "v-phantom-30";
    static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

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
            st.execute("INSERT INTO variants(id, tenant, title, price) VALUES ('" + STOCKED + "','" + TENANT + "','Stocked', 50.00)");
            st.execute("INSERT INTO variants(id, tenant, title, price) VALUES ('" + NEVER_STOCKED + "','" + TENANT + "','Phantom', 50.00)");
        }
        try (Connection c = PayDb.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE idempotency_keys, payment_intents, entries, transactions, accounts RESTART IDENTITY CASCADE");
        }
        // only ONE of the two products was ever received into this location
        Orders.receiveStock(TENANT, LOC, STOCKED, 500, T0);
    }

    /**
     * LESSON 1 · THE BATCH SURVIVES A POISONED ROW.
     *
     * Ten healthy subscriptions and one that cannot possibly be fulfilled. The
     * bad one is deliberately created FIRST, so a pass that dies on it takes
     * every other customer's renewal down with it.
     */
    @Test
    void lesson1_one_unfulfillable_subscription_does_not_stop_the_batch() throws Exception {
        // the poison, first in the queue
        Billing.subscribe(TENANT, 4000L, NEVER_STOCKED, LOC, 30, T0);
        for (int i = 1; i <= 10; i++) {
            Billing.subscribe(TENANT, 4000L + i, STOCKED, LOC, 30, T0.plusSeconds(i));
        }

        Instant due = T0.plus(Duration.ofDays(1));
        Billing.Report report = assertDoesNotThrow(() -> Billing.renewOnce(due, 100),
                "a renewal pass must not throw: one bad subscription is not an outage");

        assertEquals(10, report.renewed(), "every healthy subscription was billed");
        assertEquals(10, paidInvoices(), "and each of them has an invoice to show for it");
        System.out.println("lesson 1: 1 unfulfillable + 10 healthy -> " + report.renewed()
                + " billed, batch survived");
    }

    /**
     * LESSON 2 · A FAILURE THE SYSTEM CAUSED IS NOT THE CUSTOMER'S FAULT.
     *
     * The unfulfillable subscription is not marked as a failed payment and is
     * not pushed into dunning, because nothing about it was a payment problem.
     * It is left alone, to be retried when someone restocks, which is the only
     * outcome that can still come good. Dunning it would end with the customer
     * cancelled for a warehouse mistake.
     */
    @Test
    void lesson2_an_internal_failure_does_not_dun_the_customer() throws Exception {
        UUID poisoned = Billing.subscribe(TENANT, 4100L, NEVER_STOCKED, LOC, 30, T0);
        Billing.renewOnce(T0.plus(Duration.ofDays(1)), 100);

        assertEquals(0, dunningAttempts(poisoned),
                "no dunning attempt: the customer's card was never even asked");
        assertEquals("active", statusOf(poisoned),
                "and the subscription is still alive, waiting for the stock to come back");
        assertEquals(0, failedInvoices(),
                "nothing is recorded as a failed payment, because no payment was attempted");

        // now someone restocks, and the very next pass bills it normally
        Orders.receiveStock(TENANT, LOC, NEVER_STOCKED, 10, T0.plus(Duration.ofDays(2)));
        Billing.Report after = Billing.renewOnce(T0.plus(Duration.ofDays(3)), 100);

        assertEquals(1, after.renewed(), "once the cause is fixed, it bills with no intervention");
        System.out.println("lesson 2: an internal failure left the customer untouched, and billed cleanly after a restock");
    }

    /**
     * LESSON 3 · A DECLINED CARD STILL DUNS, AS IT SHOULD.
     *
     * The guard above must not have made the system forgiving of real payment
     * failures. A genuine decline is the customer's side of the contract, and
     * it goes into the ladder exactly as before.
     */
    @Test
    void lesson3_a_real_decline_still_enters_dunning() throws Exception {
        UUID declined = Billing.subscribe(TENANT, 4200L, STOCKED, LOC, 30, T0);
        Checkout.declineCustomerIds.add(4200L);

        Billing.Report r = Billing.renewOnce(T0.plus(Duration.ofDays(1)), 100);

        assertEquals(1, r.failed(), "the decline is a failed period");
        assertEquals(1, dunningAttempts(declined), "and it is in the ladder");
        assertEquals("past_due", statusOf(declined));
        System.out.println("lesson 3: a genuine decline still duns, so the guard did not make the system soft");
    }

    // ------------------------------------------------------------------ helpers

    private static long paidInvoices() throws Exception {
        return one("SELECT COUNT(*) FROM invoices WHERE status = 'paid'");
    }

    private static long failedInvoices() throws Exception {
        return one("SELECT COUNT(*) FROM invoices WHERE status = 'failed'");
    }

    private static long dunningAttempts(UUID sub) throws Exception {
        return one("SELECT COUNT(*) FROM dunning_attempts WHERE subscription_id = '" + sub + "'");
    }

    private static String statusOf(UUID sub) throws Exception {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("SELECT status FROM subscriptions WHERE id = ?")) {
            ps.setObject(1, sub);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getString(1); }
        }
    }

    private static long one(String sql) throws Exception {
        try (Connection c = Db.open(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
    }
}
