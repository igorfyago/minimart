package dev.minimart;

import dev.minimart.commerce.Orders;
import dev.minimart.commerce.Undeliverable;
import dev.minimart.core.Db;
import dev.minimart.core.EventRuntime;
import dev.minimart.core.Ledger;
import dev.minimart.core.Migrate;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE SAGA'S RETURN LEG · the merchant compensating for a failure it did not
 * cause and could not have prevented.
 *
 * Freight already proved it fails a shipment aloud and exactly once. These
 * lessons prove what the announcement is FOR: the shop hears it, takes back
 * the "sold" its books claimed too early, returns wallet money in the same
 * commit, and records card money as a debt with a name instead of improvising
 * a refund through a rail the processor does not have. The books balance at
 * every instant in between, and the audits are asked to confirm it rather
 * than trusted to.
 */
class UndeliverableLessonTest {

    static final String TENANT = "helix", LOC = "MAD", VARIANT = "v-focus-30";
    static final Instant T0 = Instant.parse("2026-12-01T00:00:00Z");
    static final BigDecimal PRICE = new BigDecimal("89.00");

    final EventRuntime runtime =
            new EventRuntime(Undeliverable.TOPIC_SHIPMENTS, Undeliverable.CONSUMER, Undeliverable::onShipmentFailed);

    @BeforeAll
    static void migrate() throws Exception { Migrate.bootstrap(); }

    @BeforeEach
    void reset() throws Exception {
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE refund_cases, dead_letters, event_retries, outbox, handled_events, " +
                    "dunning_attempts, invoices, subscriptions, reservations, orders, entries, transactions, " +
                    "accounts, variants, tenants RESTART IDENTITY CASCADE");
            st.execute("INSERT INTO tenants(slug) VALUES ('" + TENANT + "')");
            st.execute("INSERT INTO variants(id, tenant, title, price) VALUES ('" + VARIANT + "','" + TENANT
                    + "','Focus', " + PRICE + ")");
        }
        Orders.receiveStock(TENANT, LOC, VARIANT, 10, T0);
    }

    /**
     * LESSON 1 · WALLET MONEY GOES BACK IN THE SAME COMMIT AS THE GOODS, AND
     * NO NUMBER OF REDELIVERIES MAKES IT GO BACK TWICE.
     *
     * The runtime's claim absorbs the same delivery; the derived transaction
     * id absorbs the same ORDER under a fresh key. After both have been
     * tried, the customer has exactly their money, the shelf has exactly its
     * units, revenue holds exactly nothing, and all three audits agree the
     * books stayed honest through the whole exchange.
     */
    @Test
    void lesson1_wallet_refund_is_one_commit_and_one_time() throws Exception {
        UUID orderId = UUID.randomUUID();
        Orders.fundWallet(TENANT, 7, PRICE, T0);
        assertInstanceOf(Orders.Ok.class, Orders.submit(orderId, TENANT, 7, VARIANT, LOC, 1, T0));
        Orders.fulfil(orderId, T0);
        assertEquals("fulfilled", orderState(orderId));

        assertEquals(EventRuntime.Result.HANDLED,
                runtime.apply("shipment.failed:s1", failed(orderId), T0));
        assertEquals(EventRuntime.Result.DUPLICATE,
                runtime.apply("shipment.failed:s1", failed(orderId), T0));
        assertEquals(EventRuntime.Result.HANDLED,
                runtime.apply("shipment.failed:s1:replayed", failed(orderId), T0),
                "a fresh key is a new claim; the derived tx is what keeps the money still");

        try (Connection c = Db.open()) {
            assertEquals(0, PRICE.compareTo(Ledger.balance(c, Orders.wallet(7))), "the customer is whole");
            assertEquals(0, BigDecimal.ZERO.compareTo(Ledger.balance(c, Orders.revenue(TENANT))),
                    "revenue no longer claims the sale");
            assertEquals(10, Ledger.balance(c, Orders.onHand(LOC, VARIANT)).longValue(), "the shelf is whole");
            assertEquals(0, Ledger.balance(c, Orders.sold(LOC, VARIANT)).longValue());
            assertTrue(Ledger.sumZeroViolations(c).isEmpty() && Ledger.driftedAccounts(c).isEmpty(),
                    "the books balanced at every instant of the exchange");
        }
        assertEquals("undeliverable", orderState(orderId));
        assertEquals("refunded", scalar("SELECT status FROM refund_cases WHERE order_id = '" + orderId + "'"));
        assertEquals("1", scalar("SELECT COUNT(*) FROM refund_cases"), "one verdict per order, ever");
        assertEquals("1", scalar("SELECT COUNT(*) FROM outbox WHERE event_key = 'order.undeliverable:"
                + orderId + "'"));
    }

    /**
     * LESSON 5 · "TOO EARLY TO KNOW" IS NOT "NOTHING TO GIVE BACK".
     *
     * By construction a shipment.failed cannot precede its order being
     * fulfilled: freight learns the order exists from the same commit that
     * fulfils it. This lesson exists because the guard must not merely BET on
     * that causality: an early arrival is answered RETRY, not swallowed, so a
     * transient interleaving retries into the correct compensation, and a
     * real contract break would surface in the dead letters instead of as a
     * customer who paid for nothing while every audit stayed green.
     */
    @Test
    void lesson5_an_early_failure_retries_into_the_refund() throws Exception {
        UUID orderId = UUID.randomUUID();
        Orders.fundWallet(TENANT, 7, PRICE, T0);
        assertInstanceOf(Orders.Ok.class, Orders.submit(orderId, TENANT, 7, VARIANT, LOC, 1, T0));
        assertEquals("reserved", orderState(orderId));

        assertEquals(EventRuntime.Result.RETRY,
                runtime.apply("shipment.failed:early", failed(orderId), T0),
                "an order still reserved is too early to know, never a fact to swallow");
        assertEquals("0", scalar("SELECT COUNT(*) FROM refund_cases"));

        Orders.fulfil(orderId, T0);
        assertEquals(1, runtime.retryPending(T0), "the retry lands once the world catches up");
        assertEquals("undeliverable", orderState(orderId));
        assertEquals("refunded", scalar("SELECT status FROM refund_cases WHERE order_id = '" + orderId + "'"));
        try (Connection c = Db.open()) {
            assertEquals(0, PRICE.compareTo(Ledger.balance(c, Orders.wallet(7))), "the customer is whole");
        }
    }

    /**
     * LESSON 2 · CARD MONEY IS NOT OURS TO MOVE, SO THE DEBT IS NAMED INSTEAD.
     *
     * The capture stands at the processor and minipay has no refund rail. The
     * honest compensation restocks what is provably here and opens a case ·
     * amount, intent id, status due · where the audit surface and eventually
     * a rail will find it. Inventing the money movement from here would be
     * the exact guess the SagaDriver's refusal list exists to forbid.
     */
    @Test
    void lesson2_card_money_becomes_a_named_debt_not_a_guess() throws Exception {
        UUID orderId = UUID.randomUUID();
        assertInstanceOf(Orders.Ok.class, Orders.submit(orderId, TENANT, 7, VARIANT, LOC, 2, T0, false));
        Orders.fulfil(orderId, T0);

        assertEquals(EventRuntime.Result.HANDLED,
                runtime.apply("shipment.failed:s2", failed(orderId), T0));

        assertEquals("undeliverable", orderState(orderId));
        try (Connection c = Db.open()) {
            assertEquals(10, Ledger.balance(c, Orders.onHand(LOC, VARIANT)).longValue(), "the shelf is whole");
            try {
                assertEquals(0, BigDecimal.ZERO.compareTo(Ledger.balance(c, Orders.revenue(TENANT))),
                        "no local money leg existed, so none may appear now");
            } catch (IllegalArgumentException noAccount) {
                // even stronger: card money never touched these books at all,
                // so there is no revenue account for a wrong leg to hide in
            }
            assertTrue(Ledger.sumZeroViolations(c).isEmpty() && Ledger.driftedAccounts(c).isEmpty(),
                    "and whatever legs did post, they sum to zero and drift nowhere");
        }
        assertEquals("due", scalar("SELECT status FROM refund_cases WHERE order_id = '" + orderId + "'"));
        assertEquals("pi_" + orderId,
                scalar("SELECT intent_id FROM refund_cases WHERE order_id = '" + orderId + "'"));
    }

    /**
     * LESSON 3 · AN ORDER WITH NOTHING STANDING GETS NOTHING BACK.
     *
     * An aborted order was already made whole by abort() itself. A
     * shipment.failed arriving about it afterwards · a corrupted replay, a
     * bug at freight · must be swallowed as a fact rather than compensated as
     * a failure, because compensating twice is just the original defect
     * wearing the fix's clothes.
     */
    @Test
    void lesson3_a_dead_order_is_not_compensated_again() throws Exception {
        UUID orderId = UUID.randomUUID();
        Orders.fundWallet(TENANT, 7, PRICE, T0);
        assertInstanceOf(Orders.Ok.class, Orders.submit(orderId, TENANT, 7, VARIANT, LOC, 1, T0));
        Orders.abort(orderId, T0);

        assertEquals(EventRuntime.Result.HANDLED,
                runtime.apply("shipment.failed:s3", failed(orderId), T0));

        assertEquals("aborted", orderState(orderId));
        assertEquals("0", scalar("SELECT COUNT(*) FROM refund_cases"));
        try (Connection c = Db.open()) {
            assertEquals(0, PRICE.compareTo(Ledger.balance(c, Orders.wallet(7))),
                    "abort already returned this; a second return would mint money");
        }
    }

    /**
     * LESSON 4 · A FAILURE NOTICE ABOUT AN ORDER THAT DOES NOT EXIST IS A
     * DEFECT, AND DEFECTS GO WHERE PEOPLE LOOK.
     *
     * Freight only ships what the shop announced, so this shape can never
     * succeed however often it retries. The runtime walks it through its
     * bounded retries into the dead letters, payload and error attached,
     * which is the difference between a poison message and a mystery.
     */
    @Test
    void lesson4_an_unknown_order_is_buried_not_spun() throws Exception {
        String payload = failed(UUID.randomUUID());
        // one delivery, then redeliveries and retry passes, the way a topic
        // actually treats a record that keeps failing. Burial CLAIMS the
        // event, so the delivery after it comes back DUPLICATE: the runtime
        // has stopped grinding, which is precisely the behaviour under test.
        for (int i = 0; i <= EventRuntime.DEFAULT_MAX_ATTEMPTS; i++) {
            runtime.apply("shipment.failed:ghost", payload, T0);
            runtime.retryPending(T0);
        }
        assertEquals(1, EventRuntime.deadLetterCount(Undeliverable.CONSUMER), "the defect must surface, not spin");
        assertEquals(0, EventRuntime.pendingRetryCount(Undeliverable.CONSUMER), "and nothing keeps grinding");
        assertEquals("0", scalar("SELECT COUNT(*) FROM refund_cases"));
    }

    // ---------------------------------------------------------------- helpers

    /** Byte-for-byte the shape freight's terminal() announces. */
    private static String failed(UUID orderId) {
        return "{\"type\":\"shipment.failed\",\"eventKey\":\"shipment.failed:" + orderId
                + "\",\"shipmentId\":\"" + UUID.randomUUID() + "\",\"orderId\":\"" + orderId
                + "\",\"reason\":\"every onboarded carrier rejected the parcel\",\"at\":\"" + T0 + "\"}";
    }

    private static String orderState(UUID orderId) throws Exception {
        return scalar("SELECT state FROM orders WHERE id = '" + orderId + "'");
    }

    private static String scalar(String sql) throws Exception {
        try (Connection c = Db.open(); var st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "no row for: " + sql);
            return rs.getString(1);
        }
    }
}
