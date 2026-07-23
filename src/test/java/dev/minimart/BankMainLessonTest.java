package dev.minimart;

import com.sun.net.httpserver.HttpServer;
import dev.minimart.commerce.Checkout;
import dev.minimart.commerce.Orders;
import dev.minimart.core.Db;
import dev.minimart.core.Ledger;
import dev.minimart.core.Migrate;
import org.junit.jupiter.api.*;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE SECOND RAIL · main account.
 *
 * The shopper chose "main acct" at the till: the purchase must leave their
 * EUR statement at the bank as a named debit — the charge rides
 * /api/main/charge with the order-derived reference, and this shop's ledger
 * carries not one money leg, exactly like the card rail. The bank double
 * pins what the shop SENDS and what it does with the answer.
 */
class BankMainLessonTest {

    static HttpServer bank;
    static final String TENANT = "helix";
    static final String LOC = "MAD";
    static final String VARIANT = "v-mots-10mg";
    static final Instant T0 = Instant.parse("2026-03-01T09:00:00Z");
    static volatile String lastChargeBody;
    static volatile int mainCharges;

    @BeforeAll
    static void boot() throws Exception {
        Migrate.run();
        bank = HttpServer.create(new InetSocketAddress(0), 0);
        bank.createContext("/api/main/charge", ex -> {
            mainCharges++;
            lastChargeBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String answer = lastChargeBody.contains("\"customer\":13")
                    ? "{\"charged\":false,\"reason\":\"insufficient funds\"}"
                    : "{\"charged\":true}";
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
    void theDebitLandsOnTheStatementNamedForTheShop() throws Exception {
        // LESSON 1: chose main acct, the bank debits HIM, the reference is
        // the order's own — a retried checkout re-asks the same money.
        Orders.receiveStock(TENANT, LOC, VARIANT, 10, T0);
        UUID orderId = UUID.randomUUID();

        var placed = Checkout.placeMode(orderId, TENANT, 10L, VARIANT, LOC, 2, T0, "bank_main");
        assertInstanceOf(Checkout.Placed.class, placed);

        assertTrue(lastChargeBody.contains("\"customer\":10"), "the bank was asked to debit HIM");
        assertTrue(lastChargeBody.contains("\"amount\":\"80\""), "for the order's amount · sent " + lastChargeBody);
        assertTrue(lastChargeBody.contains("\"merchant\":\"minimart\""),
                "the statement names the shop, not a mystery till · sent " + lastChargeBody);
        assertTrue(lastChargeBody.contains("reference"), "idempotency-keyed by the order");

        try (Connection c = Db.open()) {
            assertEquals(2, Ledger.balance(c, Orders.reserved(LOC, VARIANT)).intValueExact());
            assertEquals(0, count(c, "SELECT COUNT(*) FROM accounts WHERE ref = '" + Orders.holds(TENANT) + "'"),
                    "the shop never touches main-account money either");
        }
    }

    @Test
    void anUnderfundedMainAccountGivesTheGoodsBack() throws Exception {
        // LESSON 2: customer 13 is the bank double's empty account — an
        // honest "no", the goods return, the reason says why.
        Orders.receiveStock(TENANT, LOC, VARIANT, 10, T0);
        UUID orderId = UUID.randomUUID();

        var r = Checkout.placeMode(orderId, TENANT, 13L, VARIANT, LOC, 3, T0, "bank_main");
        assertInstanceOf(Checkout.Rejected.class, r);
        assertTrue(((Checkout.Rejected) r).reason().contains("insufficient"),
                "the reason names the refusal · got " + ((Checkout.Rejected) r).reason());

        try (Connection c = Db.open()) {
            assertEquals(10, Ledger.balance(c, Orders.onHand(LOC, VARIANT)).intValueExact(),
                    "declined: every unit back on the shelf");
        }
    }

    @Test
    void anUnreachableBankReleasesTheGoodsOnThisRailToo() throws Exception {
        // LESSON 3: same headline as the card rail's lesson 3 — a timeout is
        // not a decline, the goods come back, the maybe is written down.
        Orders.receiveStock(TENANT, LOC, VARIANT, 10, T0);
        String real = Checkout.bankBaseUrl;
        Checkout.bankBaseUrl = "http://localhost:1";
        try {
            UUID orderId = UUID.randomUUID();
            var r = Checkout.placeMode(orderId, TENANT, 10L, VARIANT, LOC, 3, T0, "bank_main");
            assertInstanceOf(Checkout.Rejected.class, r);
            try (Connection c = Db.open()) {
                assertEquals(10, Ledger.balance(c, Orders.onHand(LOC, VARIANT)).intValueExact());
            }
        } finally {
            Checkout.bankBaseUrl = real;
        }
    }

    private static int count(Connection c, String sql) throws Exception {
        try (var rs = c.createStatement().executeQuery(sql)) { rs.next(); return rs.getInt(1); }
    }
}
