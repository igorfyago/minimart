package dev.minimart.http;

import dev.minifreight.FreightApi;
import dev.minifreight.FreightConsumer;
import dev.minifreight.FreightDb;
import dev.minimart.commerce.Orders;
import dev.minimart.commerce.Undeliverable;
import dev.minimart.core.Db;
import dev.minimart.core.EventRuntime;
import dev.minimart.core.Migrate;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE BUYER'S VIEW, PROVEN OVER THE WIRE.
 *
 * /api/my/orders is the first endpoint on this surface whose customer is a
 * PERSON rather than an operator, so its lessons are about what a person
 * needs to stay told: their orders answer even when the logistics service
 * does not, an undeliverable order says what happened to the money, and a
 * signed-in identity beats whatever customer number the page claims ·
 * because the alternative is anyone reading anyone's orders by editing a
 * query string.
 */
class MyOrdersLessonTest {

    static final String TENANT = "helix", LOC = "MAD", VARIANT = "v-focus-30";
    static final Instant T0 = Instant.parse("2026-12-01T00:00:00Z");
    static final BigDecimal PRICE = new BigDecimal("89.00");

    static com.sun.net.httpserver.HttpServer mart;
    static com.sun.net.httpserver.HttpServer freight;
    static int martPort;
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void boot() throws Exception {
        Migrate.bootstrap();
        FreightDb.bootstrap();
        mart = MartApi.start(0);
        martPort = mart.getAddress().getPort();
        freight = FreightApi.start(0);
        MartApi.freightBaseUrl = "http://localhost:" + freight.getAddress().getPort();
    }

    @AfterAll
    static void shutdown() {
        if (mart != null) mart.stop(0);
        if (freight != null) freight.stop(0);
        MartApi.identity = CallerIdentity.ANONYMOUS;
    }

    @BeforeEach
    void reset() throws Exception {
        MartApi.identity = CallerIdentity.ANONYMOUS;
        MartApi.freightBaseUrl = "http://localhost:" + freight.getAddress().getPort();
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE refund_cases, dead_letters, event_retries, outbox, handled_events, " +
                    "dunning_attempts, invoices, subscriptions, reservations, orders, entries, transactions, " +
                    "accounts, variants, tenants RESTART IDENTITY CASCADE");
            st.execute("INSERT INTO tenants(slug) VALUES ('" + TENANT + "')");
            st.execute("INSERT INTO variants(id, tenant, title, price) VALUES ('" + VARIANT + "','" + TENANT
                    + "','Focus Stack · 30 day', " + PRICE + ")");
        }
        try (Connection c = FreightDb.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE shipments, carrier_steps, tracking_events, outbox, handled_events RESTART IDENTITY CASCADE");
        }
        Orders.receiveStock(TENANT, LOC, VARIANT, 10, T0);
    }

    /**
     * LESSON 1 · ONE ANSWER: THE ORDER, THE PARCEL, AND WHO IT BELONGS TO.
     *
     * The order comes from the shop's books, the parcel from freight's, over
     * HTTP, composed server-side · the browser asks one question and never
     * has to know the estate is several services. And the answer is scoped:
     * another customer's orders are simply not in it.
     */
    @Test
    void lesson1_the_order_and_its_parcel_answer_together() throws Exception {
        UUID orderId = placeFulfilled(7);
        FreightConsumer.apply("order.fulfilled:" + orderId, orderFulfilled(orderId, 7));

        String body = get("/api/my/orders?customer=7", null);
        assertTrue(body.contains("\"id\":\"" + orderId + "\""));
        assertTrue(body.contains("\"state\":\"fulfilled\""));
        assertTrue(body.contains("\"shipment\":{"), "the parcel rides in the same answer");
        assertTrue(body.contains("\"tracking\":\"live\""));

        assertFalse(get("/api/my/orders?customer=8", null).contains(orderId.toString()),
                "somebody else's view has none of my orders in it");
    }

    /**
     * LESSON 2 · FREIGHT BEING DOWN COSTS THE PARCEL COLUMN, NEVER THE ORDERS.
     *
     * A buyer asking "what did I order" must not get a 500 because a
     * logistics sidecar is rebooting. The answer degrades by exactly one
     * field, and says so, because a page silently missing its tracking looks
     * identical to a parcel that is nowhere.
     */
    @Test
    void lesson2_orders_answer_while_freight_does_not() throws Exception {
        UUID orderId = placeFulfilled(7);
        MartApi.freightBaseUrl = "http://localhost:1";

        String body = get("/api/my/orders?customer=7", null);
        assertTrue(body.contains("\"id\":\"" + orderId + "\""), "the order still answers");
        assertTrue(body.contains("\"shipment\":null"));
        assertTrue(body.contains("\"tracking\":\"unavailable\""), "and the degradation is SAID");
    }

    /**
     * LESSON 3 · AN UNDELIVERABLE ORDER TELLS THE BUYER ABOUT THE MONEY.
     *
     * "Could not be delivered" is half an answer; the half that matters is
     * whether you were refunded. The refund column comes from the shop's own
     * case table, written in the same commit as the compensation, so the page
     * can say 'refunded' and mean it.
     */
    @Test
    void lesson3_undeliverable_says_what_happened_to_the_money() throws Exception {
        UUID orderId = placeFulfilled(7);
        new EventRuntime(Undeliverable.TOPIC_SHIPMENTS, Undeliverable.CONSUMER, Undeliverable::onShipmentFailed)
                .apply("shipment.failed:" + orderId,
                        "{\"type\":\"shipment.failed\",\"eventKey\":\"shipment.failed:" + orderId
                                + "\",\"orderId\":\"" + orderId + "\",\"reason\":\"every carrier said no\",\"at\":\"" + T0 + "\"}",
                        T0);

        String body = get("/api/my/orders?customer=7", null);
        assertTrue(body.contains("\"state\":\"undeliverable\""));
        assertTrue(body.contains("\"refund\":\"refunded\""), "wallet money went back, and the answer says so");
    }

    /**
     * LESSON 4 · A TOKEN BEATS A QUERY STRING.
     *
     * Anonymous access stays open, this estate demos without logins. But the
     * moment a token names a customer, the query string stops being consulted:
     * anything else would make ?customer= a read-anyone's-orders parameter for
     * signed-in users, which is the polite name for an IDOR.
     */
    @Test
    void lesson4_identity_beats_the_query_string() throws Exception {
        UUID mine = placeFulfilled(7);
        MartApi.identity = header -> "Bearer let-me-in".equals(header) ? Optional.of(42L) : Optional.empty();

        String asFortyTwo = get("/api/my/orders?customer=7", "Bearer let-me-in");
        assertFalse(asFortyTwo.contains(mine.toString()),
                "the token says 42, so 7's orders are not on the page whatever the URL claims");
        assertTrue(get("/api/my/orders?customer=7", null).contains(mine.toString()),
                "anonymous plus ?customer= still works, the estate demos without logins");
    }

    // ---------------------------------------------------------------- helpers

    private static UUID placeFulfilled(long customer) throws Exception {
        UUID orderId = UUID.randomUUID();
        Orders.fundWallet(TENANT, customer, PRICE, T0);
        assertInstanceOf(Orders.Ok.class, Orders.submit(orderId, TENANT, customer, VARIANT, LOC, 1, T0));
        Orders.fulfil(orderId, T0);
        return orderId;
    }

    private static String orderFulfilled(UUID orderId, long customer) {
        return "{\"type\":\"order.fulfilled\",\"eventKey\":\"order.fulfilled:" + orderId
                + "\",\"orderId\":\"" + orderId + "\",\"tenant\":\"" + TENANT
                + "\",\"customer\":" + customer + ",\"variant\":\"" + VARIANT
                + "\",\"location\":\"" + LOC + "\",\"qty\":1,\"amount\":\"89.00\",\"at\":\"" + T0 + "\"}";
    }

    private static String get(String path, String auth) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + martPort + path)).GET();
        if (auth != null) b.header("Authorization", auth);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).body();
    }
}
