package dev.minimart;

import com.sun.net.httpserver.HttpServer;
import dev.minimart.commerce.Checkout;
import dev.minimart.commerce.Orders;
import dev.minimart.commerce.ReservationSweeper;
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
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE TWO SERVICES, TALKING.
 *
 * minimart holds the goods, minipay holds the money, and a socket separates
 * them. These lessons are about the seam: what happens when it works, and what
 * happens when the far side is not there.
 */
class CheckoutLessonTest {

    static HttpServer pay;
    static final int PAY_PORT = 18100;
    static final String TENANT = "helix";
    static final String LOC = "MAD";
    static final String VARIANT = "v-mots-10mg";
    static final Instant T0 = Instant.parse("2026-03-01T09:00:00Z");

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
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE reservations, orders, entries, transactions, accounts, variants, tenants RESTART IDENTITY CASCADE");
            st.execute("INSERT INTO tenants(slug) VALUES ('" + TENANT + "')");
            st.execute("INSERT INTO variants(id, tenant, title, price) VALUES ('" + VARIANT + "','" + TENANT + "','MOTS-c 10mg', 40.00)");
        }
        try (Connection c = PayDb.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE idempotency_keys, payment_intents, entries, transactions, accounts RESTART IDENTITY CASCADE");
        }
    }

    /** LESSON 1 · the happy path across two services and two databases. */
    @Test
    void lesson1_goods_here_money_there() throws Exception {
        Orders.receiveStock(TENANT, LOC, VARIANT, 10, T0);
        UUID orderId = UUID.randomUUID();

        var placed = Checkout.place(orderId, TENANT, 42L, VARIANT, LOC, 2, T0);
        assertInstanceOf(Checkout.Placed.class, placed);

        // goods held in minimart, money held in minipay, and nowhere else
        try (Connection c = Db.open()) {
            assertEquals(8, Ledger.balance(c, Orders.onHand(LOC, VARIANT)).intValueExact());
            assertEquals(2, Ledger.balance(c, Orders.reserved(LOC, VARIANT)).intValueExact());
            // stronger than a zero balance: the store has no money account at all
            assertEquals(0, count(c, "SELECT COUNT(*) FROM accounts WHERE ref = '" + Orders.holds(TENANT) + "'"),
                    "the store never touches the money");
        }
        try (Connection c = PayDb.open()) {
            assertEquals(new BigDecimal("80.00"),
                    Ledger.balance(c, PaymentIntents.holds(TENANT)).stripTrailingZeros().setScale(2));
        }

        assertTrue(Checkout.ship(orderId, T0.plusSeconds(3600)));
        try (Connection c = Db.open()) {
            assertEquals(2, Ledger.balance(c, Orders.sold(LOC, VARIANT)).intValueExact());
            assertEquals(0, Ledger.balance(c, Orders.reserved(LOC, VARIANT)).intValueExact());
        }
        try (Connection c = PayDb.open()) {
            assertEquals(0, new java.math.BigDecimal("80.00").compareTo(owedToMerchant("helix")),
                    "captured: the merchant is now OWED it. A capture takes the cardholder's money and does not "
                    + "pay the merchant, who is paid later by a settlement run, net of fees.");
            assertEquals(0, Ledger.balance(c, PaymentIntents.holds(TENANT)).signum());
            assertTrue(Ledger.sumZeroViolations(c).isEmpty());
        }
        System.out.println("lesson 1: goods reserved in minimart, money held in minipay, ship captures both sides");
    }

    /** LESSON 2 · THE HEADLINE. The processor is gone, so the goods must come back. */
    @Test
    void lesson2_unreachable_processor_releases_the_goods() throws Exception {
        Orders.receiveStock(TENANT, LOC, VARIANT, 10, T0);
        Checkout.payBaseUrl = "http://localhost:1";      // nothing is listening there

        UUID orderId = UUID.randomUUID();
        var r = Checkout.place(orderId, TENANT, 43L, VARIANT, LOC, 3, T0);

        assertInstanceOf(Checkout.Rejected.class, r);
        try (Connection c = Db.open()) {
            assertEquals(10, Ledger.balance(c, Orders.onHand(LOC, VARIANT)).intValueExact(),
                    "every unit is back on the shelf");
            assertEquals(0, Ledger.balance(c, Orders.reserved(LOC, VARIANT)).intValueExact(),
                    "no phantom hold survives a dead processor");
            assertEquals("aborted", orderState(c, orderId));
            assertTrue(ReservationSweeper.reservedMismatches(c).isEmpty());
            assertTrue(Ledger.sumZeroViolations(c).isEmpty());
        }
        System.out.println("lesson 2: processor unreachable · stock compensated back, no phantom reservation");
    }

    /** LESSON 3 · retrying the whole checkout authorises exactly once. */
    @Test
    void lesson3_retrying_checkout_charges_once() throws Exception {
        Orders.receiveStock(TENANT, LOC, VARIANT, 10, T0);
        UUID orderId = UUID.randomUUID();

        var first = Checkout.place(orderId, TENANT, 44L, VARIANT, LOC, 1, T0);
        assertInstanceOf(Checkout.Placed.class, first);
        // the client did not hear back and tries the identical checkout again
        var second = Checkout.place(orderId, TENANT, 44L, VARIANT, LOC, 1, T0);
        assertInstanceOf(Checkout.Rejected.class, second, "the order id gate stops the second reservation");

        try (Connection c = Db.open()) {
            assertEquals(9, Ledger.balance(c, Orders.onHand(LOC, VARIANT)).intValueExact(), "reserved once");
            assertEquals(1, count(c, "SELECT COUNT(*) FROM reservations WHERE order_id = '" + orderId + "'"));
        }
        try (Connection c = PayDb.open()) {
            assertEquals(new BigDecimal("40.00"),
                    Ledger.balance(c, PaymentIntents.holds(TENANT)).stripTrailingZeros().setScale(2),
                    "authorised once");
            assertEquals(1, count(c, "SELECT COUNT(*) FROM payment_intents"));
        }
        System.out.println("lesson 3: checkout retried · one reservation, one authorisation");
    }

    /** LESSON 4 · an abandoned cart must release BOTH sides of the seam.
     *  Returning the goods while leaving the authorisation standing would hold a
     *  customer's money for a product that went back on the shelf. */
    @Test
    void lesson4_expiry_releases_the_goods_and_the_money() throws Exception {
        Orders.receiveStock(TENANT, LOC, VARIANT, 10, T0);
        UUID orderId = UUID.randomUUID();
        assertInstanceOf(Checkout.Placed.class,
                Checkout.place(orderId, TENANT, 45L, VARIANT, LOC, 2, T0));

        try (Connection c = PayDb.open()) {
            assertEquals(new BigDecimal("80.00"),
                    Ledger.balance(c, PaymentIntents.holds(TENANT)).stripTrailingZeros().setScale(2),
                    "money is held while the cart lives");
        }

        // the customer wandered off; the sweeper reclaims it after the TTL
        int released = ReservationSweeper.sweepOnce(T0.plus(java.time.Duration.ofHours(2)), 100);
        assertEquals(1, released);

        try (Connection c = Db.open()) {
            assertEquals(10, Ledger.balance(c, Orders.onHand(LOC, VARIANT)).intValueExact(), "goods returned");
            assertEquals("aborted", orderState(c, orderId));
        }
        try (Connection c = PayDb.open()) {
            assertEquals(0, Ledger.balance(c, PaymentIntents.holds(TENANT)).signum(),
                    "and the authorisation was voided, not left standing");
            assertEquals(0, Ledger.balance(c, PaymentIntents.source("cust_45")).signum(),
                    "the customer was never really charged");
        }
        System.out.println("lesson 4: abandoned cart · goods back on the shelf AND the hold voided at the processor");
    }

    private static String orderState(Connection c, UUID id) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT state FROM orders WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getString(1); }
        }
    }

    private static long count(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next(); return rs.getLong(1);
        }
    }

    /** What the processor OWES this merchant. A capture creates the debt; a
     *  settlement run turns it into a payout, net of fees. */
    private static java.math.BigDecimal owedToMerchant(String merchant) throws Exception {
        try (java.sql.Connection c = dev.minipay.PayDb.open()) {
            return dev.minimart.core.Ledger.balance(c, dev.minipay.Settlements.receivable(merchant));
        }
    }
}
