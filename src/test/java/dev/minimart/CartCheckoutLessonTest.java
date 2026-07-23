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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE CART · one checkout, many lines, one charge.
 *
 * A shopper does not buy a product, they buy a BASKET. These lessons pin the
 * group checkout the way BankMainLessonTest pins the single-line rail: every
 * line reserved by the existing machinery (a retried group re-derives the
 * same per-line ids and places nothing twice), one charge for the basket
 * total, and a failure anywhere puts everything back — no half a basket.
 */
class CartCheckoutLessonTest {

    static HttpServer bank;
    static final String TENANT = "helix";
    static final String LOC = "MAD";
    static final Instant T0 = Instant.parse("2026-03-01T09:00:00Z");
    static volatile String lastChargeBody;
    static volatile int charges;

    @BeforeAll
    static void boot() throws Exception {
        Migrate.run();
        bank = HttpServer.create(new InetSocketAddress(0), 0);
        bank.createContext("/api/main/charge", ex -> {
            charges++;
            lastChargeBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String answer = "{\"charged\":true}";
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
        charges = 0;
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE reservations, orders, entries, transactions, accounts, variants, tenants, remote_steps RESTART IDENTITY CASCADE");
            st.execute("INSERT INTO tenants(slug) VALUES ('" + TENANT + "')");
            st.execute("INSERT INTO variants(id, tenant, title, price) VALUES " +
                    "('v-focus-30','" + TENANT + "','Focus Stack · 30 day', 89.00)," +
                    "('v-sleep-30','" + TENANT + "','Sleep Stack · 30 day', 69.00)");
        }
    }

    @Test
    void theBasketReservesEveryLineAndChargesTheTotalOnce() throws Exception {
        Orders.receiveStock(TENANT, LOC, "v-focus-30", 10, T0);
        Orders.receiveStock(TENANT, LOC, "v-sleep-30", 10, T0);
        UUID group = UUID.randomUUID();

        var r = Checkout.placeCart(group, TENANT, 10L,
                List.of(new Checkout.Line("v-focus-30", 2), new Checkout.Line("v-sleep-30", 1)),
                LOC, T0, "bank_main");

        assertInstanceOf(Checkout.CartPlaced.class, r, "the basket is placed · got " + r);
        assertEquals(1, charges, "ONE charge for the basket, not one per line");
        assertTrue(lastChargeBody.contains("\"amount\":\"247\""),
                "the total: 2×89 + 1×69 · sent " + lastChargeBody);

        try (Connection c = Db.open()) {
            assertEquals(2, count(c, "SELECT COUNT(*) FROM orders WHERE state='reserved'"),
                    "each line is its own order row, settled by the existing machinery");
            assertEquals(8, Ledger.balance(c, Orders.onHand(LOC, "v-focus-30")).intValueExact());
            assertEquals(9, Ledger.balance(c, Orders.onHand(LOC, "v-sleep-30")).intValueExact());
        }
    }

    @Test
    void aLineThatCannotReservePutsTheWholeBasketBack() throws Exception {
        Orders.receiveStock(TENANT, LOC, "v-focus-30", 10, T0);
        // v-sleep-30 has NO stock: the second line must fail
        UUID group = UUID.randomUUID();

        var r = Checkout.placeCart(group, TENANT, 10L,
                List.of(new Checkout.Line("v-focus-30", 1), new Checkout.Line("v-sleep-30", 1)),
                LOC, T0, "bank_main");

        assertInstanceOf(Checkout.Rejected.class, r);
        assertEquals(0, charges, "no reservation, no charge");
        try (Connection c = Db.open()) {
            assertEquals(10, Ledger.balance(c, Orders.onHand(LOC, "v-focus-30")).intValueExact(),
                    "the good line goes back too: no half a basket");
            assertEquals(0, count(c, "SELECT COUNT(*) FROM orders WHERE state='reserved'"));
        }
    }

    @Test
    void aRetriedGroupCheckoutPlacesNothingTwice() throws Exception {
        Orders.receiveStock(TENANT, LOC, "v-focus-30", 10, T0);
        Orders.receiveStock(TENANT, LOC, "v-sleep-30", 10, T0);
        UUID group = UUID.randomUUID();
        var lines = List.of(new Checkout.Line("v-focus-30", 1), new Checkout.Line("v-sleep-30", 1));

        Checkout.placeCart(group, TENANT, 10L, lines, LOC, T0, "bank_main");
        var again = Checkout.placeCart(group, TENANT, 10L, lines, LOC, T0, "bank_main");

        assertInstanceOf(Checkout.Rejected.class, again,
                "the same basket asked twice is the same basket");
        try (Connection c = Db.open()) {
            assertEquals(2, count(c, "SELECT COUNT(*) FROM orders"),
                    "still one order per line, placed once");
        }
    }

    private static int count(Connection c, String sql) throws Exception {
        try (var rs = c.createStatement().executeQuery(sql)) { rs.next(); return rs.getInt(1); }
    }
}
