package dev.minimart;

import dev.minimart.commerce.Orders;
import dev.minimart.core.Db;
import dev.minimart.core.Migrate;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE SHOP'S TAPE FEED · /api/events answers what just happened in the shop,
 * newest first, in a shape a ticker can cross the screen with: what kind of
 * moment, which product, how much money, when. The bank has /api/xray/events;
 * this is the shop's own, and the estate tape reads it the same way.
 */
class EventsFeedLessonTest {

    static final String TENANT = "helix";
    static final String LOC = "MAD";
    static final String VARIANT = "v-focus-30";
    static final Instant T0 = Instant.parse("2026-05-01T09:00:00Z");

    @BeforeAll
    static void boot() throws Exception {
        Migrate.run();
    }

    @BeforeEach
    void reset() throws Exception {
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE reservations, orders, entries, transactions, accounts, variants, tenants, remote_steps, outbox RESTART IDENTITY CASCADE");
            st.execute("INSERT INTO tenants(slug) VALUES ('" + TENANT + "')");
            st.execute("INSERT INTO variants(id, tenant, title, price) VALUES ('" + VARIANT + "','" + TENANT + "','Focus Stack · 30 day', 89.00)");
        }
    }

    @Test
    void anOrderPlacedIsATapeEventWithTheMoneyInIt() throws Exception {
        Orders.receiveStock(TENANT, LOC, VARIANT, 5, T0);
        Orders.submitMode(UUID.randomUUID(), TENANT, 1010L, VARIANT, LOC, 2, T0, "psp");

        String feed = dev.minimart.http.MartApi.eventsFeed(10);

        assertTrue(feed.contains("order.placed"), "the feed names the moment · got " + feed);
        assertTrue(feed.contains("\"amount\":\"178\""), "with the money · got " + feed);
        assertTrue(feed.contains(VARIANT), "and the product");
    }

    @Test
    void theFeedAnswersNewestFirstAndHonoursItsLimit() throws Exception {
        Orders.receiveStock(TENANT, LOC, VARIANT, 20, T0);
        for (int i = 0; i < 3; i++)
            Orders.submitMode(UUID.randomUUID(), TENANT, 1010L, VARIANT, LOC, 1, T0.plusSeconds(i * 60), "psp");

        String feed = dev.minimart.http.MartApi.eventsFeed(2);

        int first = feed.indexOf("order.placed");
        int count = feed.split("order\\.placed", -1).length - 1;
        assertEquals(2, count, "the limit is honoured");
        assertTrue(first > 0, "it is a JSON array of events");
    }

    @Test
    void anEmptyShopSaysSoWithoutError() throws Exception {
        String feed = dev.minimart.http.MartApi.eventsFeed(10);
        assertEquals("[]", feed.trim(), "no events is an empty array, not a 500");
    }
}
