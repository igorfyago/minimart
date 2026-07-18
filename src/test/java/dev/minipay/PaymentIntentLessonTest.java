package dev.minipay;

import com.sun.net.httpserver.HttpServer;
import dev.minimart.core.Ledger;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * minipay · the payment processor lessons.
 *
 * Everything here goes over a real socket, because that is the only place the
 * interesting failures live: a response lost in flight, a client that retries,
 * two retries racing each other.
 */
class PaymentIntentLessonTest {

    static HttpServer server;
    static final int PORT = 18099;
    static final String BASE = "http://localhost:" + PORT;
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void boot() throws Exception {
        PayDb.bootstrap();
        server = PayApi.start(PORT);
    }

    @AfterAll
    static void stop() { if (server != null) server.stop(0); }

    @BeforeEach
    void reset() throws Exception {
        try (Connection c = PayDb.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE idempotency_keys, payment_intents, entries, transactions, accounts RESTART IDENTITY CASCADE");
        }
    }

    /** LESSON 1 · the lifecycle. Authorised money is not yet the merchant's. */
    @Test
    void lesson1_authorize_then_capture() throws Exception {
        var created = post("/v1/payment_intents", """
                {"id":"pi_1","amount":"40.00","customer":"cust_7","merchant":"helix"}""", "key-1");
        assertEquals(200, created.statusCode());
        assertTrue(created.body().contains("\"status\":\"requires_capture\""), created.body());

        // authorised: held, not yet available
        var bal = get("/v1/balance?merchant=helix");
        assertTrue(bal.body().contains("\"pending\":\"40.00\""), bal.body());
        assertTrue(bal.body().contains("\"available\":\"0.00\""), bal.body());

        var captured = post("/v1/payment_intents/pi_1/capture", "{}", null);
        assertEquals(200, captured.statusCode());
        assertTrue(captured.body().contains("\"status\":\"succeeded\""));

        // CAPTURE DOES NOT PAY THE MERCHANT. The hold is gone, because the
        // cardholder's money has been taken, and what the merchant has is a
        // RECEIVABLE: they are paid later, in a batch, net of a fee. A
        // processor that made this "available" the moment it captured would be
        // a wallet with good manners, and every merchant's payout would
        // reconcile to the wrong number.
        bal = get("/v1/balance?merchant=helix");
        assertTrue(bal.body().contains("\"pending\":\"0.00\""), bal.body());
        assertTrue(bal.body().contains("\"available\":\"0.00\""),
                "not paid out yet, because a settlement has not run: " + bal.body());
        try (Connection c = PayDb.open()) {
            assertEquals(0, new java.math.BigDecimal("40.00").compareTo(
                            Ledger.balance(c, dev.minipay.Settlements.receivable("helix"))),
                    "but it IS owed to them, in full, before any fee is taken");
        }

        try (Connection c = PayDb.open()) {
            assertTrue(Ledger.sumZeroViolations(c).isEmpty());
            assertTrue(Ledger.driftedAccounts(c).isEmpty());
        }
        System.out.println("lesson 1: authorise holds 40.00, capture makes it OWED, not paid · books balance");
    }

    /** LESSON 2 · the lost response. The retry gets the identical answer, and charges once. */
    @Test
    void lesson2_same_key_replays_the_identical_response() throws Exception {
        String body = """
                {"id":"pi_2","amount":"25.00","customer":"cust_8","merchant":"helix"}""";
        var first = post("/v1/payment_intents", body, "key-2");
        var second = post("/v1/payment_intents", body, "key-2");

        assertEquals(first.statusCode(), second.statusCode());
        assertEquals(first.body(), second.body(), "a replay returns the identical response, byte for byte");
        assertEquals("true", second.headers().firstValue("Idempotent-Replayed").orElse(""));

        try (Connection c = PayDb.open()) {
            assertEquals(new BigDecimal("25.00"),
                    Ledger.balance(c, PaymentIntents.holds("helix")).stripTrailingZeros().setScale(2),
                    "charged once, not twice");
        }
        System.out.println("lesson 2: same Idempotency-Key twice · identical body returned, one charge");
    }

    /** LESSON 3 · the key is a promise about the request, not just a token. */
    @Test
    void lesson3_same_key_different_request_is_refused() throws Exception {
        post("/v1/payment_intents", """
                {"id":"pi_3","amount":"10.00","customer":"cust_9","merchant":"helix"}""", "key-3");
        var mismatched = post("/v1/payment_intents", """
                {"id":"pi_3","amount":"999.00","customer":"cust_9","merchant":"helix"}""", "key-3");

        assertEquals(422, mismatched.statusCode(), "reusing a key for a different request is a caller bug");
        try (Connection c = PayDb.open()) {
            assertEquals(new BigDecimal("10.00"),
                    Ledger.balance(c, PaymentIntents.holds("helix")).stripTrailingZeros().setScale(2));
        }
        System.out.println("lesson 3: same key, different body · 422, and the second amount never happened");
    }

    /** LESSON 4 · cancel returns the money, and cancelling twice is harmless. */
    @Test
    void lesson4_cancel_voids_the_hold_idempotently() throws Exception {
        post("/v1/payment_intents", """
                {"id":"pi_4","amount":"60.00","customer":"cust_10","merchant":"helix"}""", "key-4");
        assertEquals(200, post("/v1/payment_intents/pi_4/cancel", "{}", null).statusCode());
        assertEquals(200, post("/v1/payment_intents/pi_4/cancel", "{}", null).statusCode(), "cancel twice is fine");

        try (Connection c = PayDb.open()) {
            assertEquals(0, Ledger.balance(c, PaymentIntents.holds("helix")).signum(), "hold released");
            assertEquals(0, Ledger.balance(c, PaymentIntents.source("cust_10")).signum(), "customer never really paid");
            assertTrue(Ledger.sumZeroViolations(c).isEmpty());
        }
        // and a captured-after-cancel attempt must not resurrect it
        assertEquals(409, post("/v1/payment_intents/pi_4/capture", "{}", null).statusCode());
        System.out.println("lesson 4: cancel voids the hold, twice is a no-op, capture after cancel refused");
    }

    /** LESSON 5 · twenty concurrent retries of one key. Exactly one charge. */
    @Test
    void lesson5_concurrent_retries_charge_once() throws Exception {
        String body = """
                {"id":"pi_5","amount":"15.00","customer":"cust_11","merchant":"helix"}""";
        AtomicInteger ok = new AtomicInteger(), refused = new AtomicInteger();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                fs.add(pool.submit(() -> {
                    go.await();
                    var r = post("/v1/payment_intents", body, "key-5");
                    if (r.statusCode() == 200) ok.incrementAndGet(); else refused.incrementAndGet();
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        }
        assertTrue(ok.get() >= 1, "at least one succeeds");
        assertEquals(20, ok.get() + refused.get());
        try (Connection c = PayDb.open()) {
            assertEquals(new BigDecimal("15.00"),
                    Ledger.balance(c, PaymentIntents.holds("helix")).stripTrailingZeros().setScale(2),
                    "twenty racing retries, one charge");
        }
        System.out.println("lesson 5: 20 concurrent retries of one key · " + ok.get() + " ok, " + refused.get() + " refused, charged once");
    }

    // ------------------------------------------------------------------ http
    private static HttpResponse<String> post(String path, String body, String idemKey) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idemKey != null) b.header("Idempotency-Key", idemKey);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(BASE + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
