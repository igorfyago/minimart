package dev.minimart;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.minimart.commerce.Billing;
import dev.minimart.commerce.Orders;
import dev.minimart.core.Db;
import dev.minimart.core.Migrate;
import org.junit.jupiter.api.*;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE SUBSCRIPTION REMEMBERS ITS RAIL.
 *
 * A shopper who subscribed while signed in chose "main acct" or "credit card"
 * at the till; the renewal must ride THAT rail, charged at their bank, not a
 * mystery processor debit a month later. These lessons pin the memory and the
 * renewal against a bank double.
 */
class SubscribeRailLessonTest {

    static HttpServer bank;
    static final String TENANT = "helix";
    static final String LOC = "MAD";
    static final String VARIANT = "v-focus-30";
    static final Instant T0 = Instant.parse("2026-04-01T09:00:00Z");
    static final AtomicInteger bankCharges = new AtomicInteger();
    static volatile String lastBankBody;

    @BeforeAll
    static void boot() throws Exception {
        Migrate.run();
        bank = HttpServer.create(new InetSocketAddress(0), 0);
        bank.createContext("/api/main/charge", ex -> {
            bankCharges.incrementAndGet();
            lastBankBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            answer(ex, "{\"charged\":true}");
        });
        bank.createContext("/api/card/charge", ex -> {
            bankCharges.incrementAndGet();
            lastBankBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            answer(ex, "{\"charged\":true,\"authorization\":\"a1\",\"last4\":\"4388\"}");
        });
        bank.start();
        dev.minimart.commerce.Checkout.bankBaseUrl = "http://localhost:" + bank.getAddress().getPort();
    }

    private static void answer(HttpExchange ex, String body) throws java.io.IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length());
        try (OutputStream os = ex.getResponseBody()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
    }

    @AfterAll
    static void stop() { if (bank != null) bank.stop(0); }

    @BeforeEach
    void reset() throws Exception {
        bankCharges.set(0);
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE reservations, orders, entries, transactions, accounts, variants, tenants, remote_steps RESTART IDENTITY CASCADE");
            st.execute("TRUNCATE subscriptions, invoices RESTART IDENTITY CASCADE");
            st.execute("INSERT INTO tenants(slug) VALUES ('" + TENANT + "')");
            st.execute("INSERT INTO variants(id, tenant, title, price) VALUES ('" + VARIANT + "','" + TENANT + "','Focus Stack · 30 day', 89.00)");
        }
    }

    @Test
    void aSubscriptionRemembersTheRailItWasTakenOutOn() throws Exception {
        Billing.subscribe(TENANT, 10L, VARIANT, LOC, 30, T0, "bank_main");

        try (Connection c = Db.open(); var rs = c.createStatement().executeQuery(
                "SELECT pay_rail FROM subscriptions WHERE customer_id = 10")) {
            assertTrue(rs.next());
            assertEquals("bank_main", rs.getString(1), "the rail is stored with the subscription");
        }
    }

    @Test
    void theRenewalRidesTheBankRailNeverTheProcessor() throws Exception {
        Orders.receiveStock(TENANT, LOC, VARIANT, 10, T0);
        Billing.subscribe(TENANT, 10L, VARIANT, LOC, 30, T0, "bank_main");

        var report = Billing.renewOnce(T0.plus(Duration.ofDays(31)), 10);

        assertEquals(1, report.renewed(), "the period bills · got " + report);
        assertEquals(1, bankCharges.get(), "the charge was asked of the BANK");
        assertTrue(lastBankBody.contains("\"customer\":10"), "for the subscriber");
        assertTrue(lastBankBody.contains("\"merchant\":\"minimart\""), "named for the shop");
    }

    @Test
    void aPspSubscriptionStillBillsThroughTheProcessor() throws Exception {
        Orders.receiveStock(TENANT, LOC, VARIANT, 10, T0);
        Billing.subscribe(TENANT, 11L, VARIANT, LOC, 30, T0);   // no rail named: psp stands

        Billing.renewOnce(T0.plus(Duration.ofDays(31)), 10);

        assertEquals(0, bankCharges.get(), "an anonymous subscriber's renewal never touches the bank");
    }
}
