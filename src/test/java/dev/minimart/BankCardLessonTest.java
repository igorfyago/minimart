package dev.minimart;

import com.sun.net.httpserver.HttpServer;
import dev.minimart.commerce.Checkout;
import dev.minimart.commerce.Orders;
import dev.minimart.core.Db;
import dev.minimart.core.Ledger;
import dev.minimart.core.Migrate;
import org.junit.jupiter.api.*;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE FULL CIRCLE, AT ITS LAST INCH · the card rail.
 *
 * A signed-in estate shopper's purchase must land on their real card at the
 * bank, never on this shop's own books. These lessons pin the seam the way
 * CheckoutLessonTest pins the psp seam: the happy path across the wire, and
 * the degrade when the far side is not there. The bank is a double — the
 * lesson is what the shop SENDS and what it does with the answer, not the
 * issuer's arithmetic, which the bank's own suite owns.
 */
class BankCardLessonTest {

    static HttpServer bank;
    static final String TENANT = "helix";
    static final String LOC = "MAD";
    static final String VARIANT = "v-mots-10mg";
    static final Instant T0 = Instant.parse("2026-03-01T09:00:00Z");
    static final AtomicInteger charges = new AtomicInteger();
    static volatile String lastChargeBody;

    @BeforeAll
    static void boot() throws Exception {
        Migrate.run();
        bank = HttpServer.create(new InetSocketAddress(0), 0);
        bank.createContext("/api/card/charge", ex -> {
            charges.incrementAndGet();
            lastChargeBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String answer = lastChargeBody.contains("\"customer\":13")
                    ? "{\"charged\":false,\"reason\":\"insufficient credit\"}"
                    : "{\"charged\":true,\"authorization\":\"a1\",\"last4\":\"4242\"}";
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, answer.length());
            try (OutputStream os = ex.getResponseBody()) { os.write(answer.getBytes(StandardCharsets.UTF_8)); }
        });
        bank.start();
        Checkout.bankBaseUrl = "http://localhost:" + bank.getAddress().getPort();
    }

    @AfterAll
    static void stop() { if (bank != null) bank.stop(0); }

    @BeforeEach
    void reset() throws Exception {
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE reservations, orders, entries, transactions, accounts, variants, tenants, remote_steps RESTART IDENTITY CASCADE");
            st.execute("INSERT INTO tenants(slug) VALUES ('" + TENANT + "')");
            st.execute("INSERT INTO variants(id, tenant, title, price) VALUES ('" + VARIANT + "','" + TENANT + "','MOTS-c 10mg', 40.00)");
        }
    }

    @Test
    void theMoneyMovesAtTheBankNeverHere() throws Exception {
        // LESSON 1: the circle closes. Goods reserved here, the charge asked
        // of the bank, and this shop's ledger carries NOT ONE money leg.
        Orders.receiveStock(TENANT, LOC, VARIANT, 10, T0);
        UUID orderId = UUID.randomUUID();

        var placed = Checkout.placeMode(orderId, TENANT, 10L, VARIANT, LOC, 2, T0, "bank_card");
        assertInstanceOf(Checkout.Placed.class, placed);

        assertTrue(lastChargeBody.contains("\"customer\":10"), "the bank was asked to charge HIM");
        assertTrue(lastChargeBody.contains("\"amount\":\"80\""), "for the order's amount · sent " + lastChargeBody);
        assertTrue(lastChargeBody.contains("authorization_id"), "idempotency-keyed by the order");

        try (Connection c = Db.open()) {
            assertEquals(8, Ledger.balance(c, Orders.onHand(LOC, VARIANT)).intValueExact());
            assertEquals(2, Ledger.balance(c, Orders.reserved(LOC, VARIANT)).intValueExact());
            // the shop's books hold NO money for a card sale, exactly as for psp
            assertEquals(0, count(c, "SELECT COUNT(*) FROM accounts WHERE ref = '" + Orders.holds(TENANT) + "'"),
                    "the shop never touches card money");
        }
        System.out.println("card lesson 1: charged at the bank, stock reserved here, no money leg on the shop's books");
    }

    @Test
    void aDeclinedCardGivesTheGoodsBack() throws Exception {
        // LESSON 2: an answer of "no" — customer 13 is the bank double's
        // declining card. The goods return to the shelf, nothing is charged
        // twice, and the refusal says why.
        Orders.receiveStock(TENANT, LOC, VARIANT, 10, T0);
        UUID orderId = UUID.randomUUID();

        var r = Checkout.placeMode(orderId, TENANT, 13L, VARIANT, LOC, 3, T0, "bank_card");
        assertInstanceOf(Checkout.Rejected.class, r);
        assertTrue(((Checkout.Rejected) r).reason().contains("declined"),
                "the reason names the decline · got " + ((Checkout.Rejected) r).reason());

        try (Connection c = Db.open()) {
            assertEquals(10, Ledger.balance(c, Orders.onHand(LOC, VARIANT)).intValueExact(),
                    "declined: every unit back on the shelf");
        }
        System.out.println("card lesson 2: a decline is an honest answer, and the goods come back");
    }

    @Test
    void anUnreachableBankReleasesTheGoods() throws Exception {
        // LESSON 3: the SAME HEADLINE as the psp path's lesson 2 — the bank
        // is gone, so the goods must come back and the maybe is written down.
        Orders.receiveStock(TENANT, LOC, VARIANT, 10, T0);
        String real = Checkout.bankBaseUrl;
        Checkout.bankBaseUrl = "http://localhost:1";    // nothing is listening
        try {
            UUID orderId = UUID.randomUUID();
            var r = Checkout.placeMode(orderId, TENANT, 10L, VARIANT, LOC, 3, T0, "bank_card");
            assertInstanceOf(Checkout.Rejected.class, r);
            try (Connection c = Db.open()) {
                assertEquals(10, Ledger.balance(c, Orders.onHand(LOC, VARIANT)).intValueExact());
            }
        } finally {
            Checkout.bankBaseUrl = real;
        }
        System.out.println("card lesson 3: bank unreachable, goods released — no sale hangs on a maybe");
    }

    private static int count(Connection c, String sql) throws Exception {
        try (var rs = c.createStatement().executeQuery(sql)) { rs.next(); return rs.getInt(1); }
    }
}
