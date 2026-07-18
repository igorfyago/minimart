package dev.minimart;

import com.sun.net.httpserver.HttpServer;
import dev.minimart.commerce.Billing;
import dev.minimart.commerce.Checkout;
import dev.minimart.commerce.Orders;
import dev.minimart.core.Db;
import dev.minimart.core.Migrate;
import dev.minimart.http.MartApi;
import dev.minipay.PayApi;
import dev.minipay.PayDb;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WHAT AN OUTSIDE CLIENT DISCOVERS ABOUT THIS API.
 *
 * These three defects were found by designing a client that lives in another
 * repository, cannot read this database, and has only the HTTP surface to go
 * on. That constraint is the whole value of the exercise: every one of these
 * had been invisible from the inside, because from the inside you can always
 * just look at the table.
 *
 * All three are the same failure in different clothes. The API knew something
 * and did not say it:
 *
 *   it reported a status it had not checked,
 *   it substituted a wall clock for an input it could not parse,
 *   and it offered a read that cannot answer the question a caller has.
 *
 * A system whose own tests all pass while its API misleads every client is a
 * system that has tested the database and called it testing the product.
 */
class ApiHonestyLessonTest {

    static HttpServer mart, pay;
    static final int MART_PORT = 18170, PAY_PORT = 18171;
    static final String TENANT = "helix", LOC = "MAD", VARIANT = "v-honest-30";
    static final Instant T0 = Instant.parse("2027-01-01T00:00:00Z");
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @BeforeAll
    static void boot() throws Exception {
        Migrate.bootstrap();
        PayDb.bootstrap();
        pay = PayApi.start(PAY_PORT);
        mart = MartApi.start(MART_PORT);
    }

    @AfterAll
    static void stop() {
        if (mart != null) mart.stop(0);
        if (pay != null) pay.stop(0);
    }

    @BeforeEach
    void reset() throws Exception {
        Checkout.payBaseUrl = "http://localhost:" + PAY_PORT;
        Checkout.declineCustomerIds.clear();
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE dead_letters, event_retries, outbox, handled_events, dunning_attempts, invoices, subscriptions, reservations, orders, entries, transactions, accounts, variants, tenants RESTART IDENTITY CASCADE");
            st.execute("INSERT INTO tenants(slug) VALUES ('" + TENANT + "')");
            st.execute("INSERT INTO variants(id, tenant, title, price) VALUES ('" + VARIANT + "','" + TENANT + "','Honest', 40.00)");
        }
        try (Connection c = PayDb.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE idempotency_keys, payment_intents, entries, transactions, accounts RESTART IDENTITY CASCADE");
        }
        Orders.receiveStock(TENANT, LOC, VARIANT, 500, T0);
    }

    /**
     * LESSON 1 · AN API MAY NOT REPORT A STATUS IT DID NOT CHECK.
     *
     * Subscribing is idempotent: asking twice returns the subscription already
     * held rather than creating a second one. That is correct. What was not
     * correct is that the endpoint answered `"status":"active"` as a hardcoded
     * string, so a customer whose subscription was in DUNNING was told it was
     * active, by an endpoint that never looked.
     *
     * A caller cannot recover from this, because from outside there is no other
     * way to find out. The response now carries the real status, and says
     * whether anything was created, which is the question an idempotent
     * endpoint is always secretly being asked.
     */
    @Test
    void lesson1_subscribe_reports_the_real_status_not_a_hardcoded_one() throws Exception {
        String first = post("/api/subscribe", body(4300L, VARIANT));
        assertTrue(first.contains("\"created\":true"), "the first call created it: " + first);
        assertTrue(first.contains("\"status\":\"active\""), first);

        // drive it into dunning with a card that will not authorise
        Checkout.declineCustomerIds.add(4300L);
        Billing.renewOnce(T0.plus(Duration.ofDays(1)), 100);
        assertEquals("past_due", statusOf(4300L), "the premise: this subscription is now in dunning");

        // ask again. The old endpoint said "active" without looking.
        String second = post("/api/subscribe", body(4300L, VARIANT));
        assertTrue(second.contains("\"created\":false"),
                "nothing was created, and the caller is told so: " + second);
        assertTrue(second.contains("\"status\":\"past_due\""),
                "AND THE STATUS IS THE REAL ONE, not a constant: " + second);
        assertFalse(second.contains("\"status\":\"active\""),
                "a customer in dunning must never be told they are active");
        System.out.println("lesson 1: resubscribing a past_due customer reports past_due, not a hardcoded active");
    }

    /**
     * LESSON 2 · A TIMESTAMP THIS SYSTEM CANNOT PARSE IS AN ERROR, NOT A GUESS.
     *
     * The doctrine of this codebase is that time is always a parameter and no
     * business logic reads the wall clock, because that is what lets a
     * compressed year run in minutes. The endpoint quietly broke it: a
     * `business_at` it could not parse was replaced with `Instant.now()`, in a
     * catch block, with no error and no log.
     *
     * The consequence is the worst available. A client with a formatting bug
     * gets a run that appears to work, is silently pinned to the wall clock,
     * and produces results that cannot be reproduced, with nothing anywhere
     * saying why. A missing timestamp still defaults to now, because a browser
     * genuinely has no business time, but a MALFORMED one is a caller bug and
     * is now told so.
     */
    @Test
    void lesson2_an_unparseable_business_time_is_refused_rather_than_silently_replaced() throws Exception {
        HttpResponse<String> bad = postRaw("/api/subscribe",
                "{\"tenant\":\"" + TENANT + "\",\"customer\":\"4301\",\"variant\":\"" + VARIANT
                        + "\",\"location\":\"" + LOC + "\",\"interval_days\":\"30\","
                        + "\"business_at\":\"last tuesday\"}");

        assertEquals(400, bad.statusCode(),
                "a timestamp the system cannot read is a caller error, not an invitation to guess");
        assertTrue(bad.body().contains("business_at"), "and it names the field: " + bad.body());

        try (Connection c = Db.open(); var st = c.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM subscriptions")) {
            rs.next();
            assertEquals(0, rs.getLong(1), "and nothing was created on a guessed clock");
        }

        // a MISSING business_at is still fine: a browser has no business time
        String ok = post("/api/subscribe",
                "{\"tenant\":\"" + TENANT + "\",\"customer\":\"4302\",\"variant\":\"" + VARIANT
                        + "\",\"location\":\"" + LOC + "\",\"interval_days\":\"30\"}");
        assertTrue(ok.contains("\"created\":true"), "an absent timestamp is not an error: " + ok);
        System.out.println("lesson 2: 'last tuesday' -> 400 naming the field; absent -> accepted");
    }

    /**
     * LESSON 3 · A READ ENDPOINT MUST BE ABLE TO ANSWER THE CALLER'S QUESTION.
     *
     * `GET /api/subscriptions` returned the twenty most recent subscriptions
     * across every customer, with no filter. It was written for a dashboard,
     * and as a dashboard it is fine. As an API it cannot answer the only
     * question a customer ever has, which is "what am I subscribed to", and
     * past twenty rows of traffic it silently stops containing them at all.
     *
     * That silence is the real defect. A client polling this endpoint to check
     * its own subscription does not get an error once the system is busy: it
     * gets an empty answer that looks exactly like "you have no subscriptions",
     * and it cannot tell the difference between the two.
     */
    @Test
    void lesson3_a_customer_can_ask_about_their_own_subscriptions() throws Exception {
        post("/api/subscribe", bodyAt(4400L, VARIANT, T0));
        // twenty five other customers subscribe afterwards, each genuinely
        // later in business time, so "most recent" means something and this
        // customer really is pushed out of the window
        for (int i = 0; i < 25; i++) {
            post("/api/subscribe", bodyAt(4500L + i, VARIANT, T0.plus(Duration.ofHours(i + 1))));
        }

        String unfiltered = get("/api/subscriptions");
        assertFalse(unfiltered.contains("\"customer\":4400"),
                "the premise: the unfiltered view has already lost this customer");

        String mine = get("/api/subscriptions?customer=4400");
        assertTrue(mine.contains("\"customer\":4400"),
                "but they can still ask about themselves, however busy the system is: " + mine);
        assertFalse(mine.contains("\"customer\":4500"), "and are shown nobody else");
        System.out.println("lesson 3: buried under 25 later subscriptions, the customer can still find their own");
    }

    // ------------------------------------------------------------------ helpers

    private static String body(long customer, String variant) { return bodyAt(customer, variant, T0); }

    private static String bodyAt(long customer, String variant, Instant at) {
        return "{\"tenant\":\"" + TENANT + "\",\"customer\":\"" + customer + "\",\"variant\":\"" + variant
                + "\",\"location\":\"" + LOC + "\",\"interval_days\":\"30\",\"business_at\":\"" + at + "\"}";
    }

    private static String statusOf(long customer) throws Exception {
        try (Connection c = Db.open();
             var ps = c.prepareStatement("SELECT status FROM subscriptions WHERE customer_id = ?")) {
            ps.setLong(1, customer);
            try (var rs = ps.executeQuery()) { rs.next(); return rs.getString(1); }
        }
    }

    private static String post(String path, String json) throws Exception {
        HttpResponse<String> r = postRaw(path, json);
        assertEquals(200, r.statusCode(), "unexpected failure from " + path + ": " + r.body());
        return r.body();
    }

    private static HttpResponse<String> postRaw(String path, String json) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + MART_PORT + path))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + MART_PORT + path))
                .GET().timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString()).body();
    }
}
