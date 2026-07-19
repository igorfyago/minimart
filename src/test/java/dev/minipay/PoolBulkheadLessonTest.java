package dev.minipay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A SLOW BANK MUST NOT BE AN OUTAGE OF THINGS THAT DO NOT NEED A BANK.
 *
 * Capture used to open a pooled connection, take a FOR UPDATE lock on the
 * payment, and then call the issuer over HTTP while holding both. Read as a
 * transaction that is what you want: nobody else can touch the payment while it
 * is being settled. Read as a system it is a way to hand a stranger the key to
 * your own front door, because the length of that transaction is decided by
 * somebody else's server.
 *
 * The pool has sixteen connections. Sixteen shoppers checking out against an
 * issuer that has stopped answering is therefore not sixteen slow checkouts, it
 * is a processor with no connections left, and the next request to arrive fails
 * whatever it wanted. Listing payments fails. Reading a key fails. A health
 * check fails, so the load balancer concludes the box is dead and moves the
 * traffic to another one, where the same thing happens. NONE OF THOSE
 * ENDPOINTS TOUCH AN ISSUER. They were taken down by a dependency they do not
 * have, which is how one slow partner becomes a full outage.
 *
 * The fix is not a bigger pool, and that is the part worth carrying elsewhere: a
 * bigger pool only moves the number at which this happens. The fix is that the
 * remote call happens with nothing held, and the state it was decided on is
 * checked again afterwards before anything is written.
 */
class PoolBulkheadLessonTest {

    static HttpServer issuer;
    static final int ISSUER_PORT = 18216;
    static final Instant T0 = Instant.parse("2027-06-01T00:00:00Z");
    static final String MERCHANT = "helix";

    /** MINIPAY_POOL's default, and the number this lesson is about. */
    static final int POOL = 16;

    /** Held shut for as long as the lesson needs the issuer to be hung, which
     *  is the honest simulation: a bank that has not refused, has not failed,
     *  and has simply not answered yet. */
    static final CountDownLatch issuerHangs = new CountDownLatch(1);
    /** Counted down as each capture ARRIVES at the issuer, so the probe below
     *  runs at the exact moment every capture is in flight. */
    static final CountDownLatch capturesArrived = new CountDownLatch(POOL);

    static String previousIssuerUrl;

    @BeforeAll
    static void boot() throws Exception {
        PayDb.bootstrap();
        issuer = HttpServer.create(new InetSocketAddress(ISSUER_PORT), 0);
        issuer.createContext("/issuer/v1/authorizations", PoolBulkheadLessonTest::issuerHandler);
        issuer.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        issuer.start();
        previousIssuerUrl = Rails.issuerBaseUrl;
        Rails.issuerBaseUrl = "http://localhost:" + ISSUER_PORT;
        Rails.issuerUnreachable = false;
    }

    @AfterAll
    static void stop() {
        issuerHangs.countDown();                       // never leave a thread parked
        Rails.issuerBaseUrl = previousIssuerUrl;
        if (issuer != null) issuer.stop(0);
    }

    @BeforeEach
    void reset() throws Exception {
        try (Connection c = PayDb.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE payment_methods, customers, idempotency_keys, payment_intents, "
                     + "entries, transactions, accounts RESTART IDENTITY CASCADE");
        }
    }

    /**
     * LESSON · A HUNG ISSUER TAKES DOWN CAPTURES AND NOTHING ELSE.
     *
     * Every connection in the pool is spoken for by a capture that is waiting on
     * a bank. The probe in the middle is the whole test: it reads a payment,
     * which is a plain SELECT that has no opinion about issuers, and it has to
     * answer at once.
     *
     * Before the fix it did not answer at all. It queued for a connection that
     * sixteen captures were holding, waited the pool's full ten seconds, and
     * failed. What the shop's console showed at that moment was not "captures
     * are slow", it was "the payment processor is down".
     */
    @Test
    void lesson_a_bank_that_stops_answering_does_not_drain_the_connection_pool() throws Exception {
        var cus = PaymentMethods.customer(MERCHANT, "bulkhead");
        var pm = PaymentMethods.attachCard(cus.id(), "ON_US", "mbc_bulkhead", "minibank credit", "7777");

        // POOL payments to capture, plus one that stays put and is only ever read
        List<String> toCapture = new ArrayList<>();
        for (int i = 0; i < POOL; i++) toCapture.add(authorised(pm.id()));
        String untouched = authorised(pm.id());

        // every capture goes to the issuer, and the issuer does not come back
        List<Thread> captures = new ArrayList<>();
        List<PaymentIntents.Result> results = java.util.Collections.synchronizedList(new ArrayList<>());
        for (String intent : toCapture) {
            Thread t = Thread.ofVirtual().start(() -> {
                try { results.add(PaymentIntents.capture(intent, T0)); }
                catch (Exception e) { results.add(new PaymentIntents.Declined("threw: " + e)); }
            });
            captures.add(t);
        }

        assertTrue(capturesArrived.await(20, TimeUnit.SECONDS),
                "all " + POOL + " captures reached the issuer, so the pool is under as much pressure as it can be");

        // THE PROBE. A read that never needed an issuer, taken while every
        // capture in the building is stuck inside one.
        long startedAt = System.nanoTime();
        PaymentIntents.Result probe;
        try {
            probe = PaymentIntents.get(untouched);
        } catch (Exception e) {
            throw new AssertionError(
                    "AN ENDPOINT THAT NEVER TOUCHES AN ISSUER COULD NOT GET A CONNECTION. The captures are "
                    + "holding all " + POOL + " of them across an HTTP call, so a slow bank has become an "
                    + "outage of unrelated functionality.", e);
        }
        long ms = (System.nanoTime() - startedAt) / 1_000_000;

        assertInstanceOf(PaymentIntents.Ok.class, probe, "the unrelated payment reads back normally");
        assertEquals("requires_capture", ((PaymentIntents.Ok) probe).status());
        assertTrue(ms < 2_000,
                "AND IT ANSWERED AT ONCE rather than queueing behind a bank: took " + ms + "ms. "
                + "A connection held across a remote call makes that wait the issuer's to decide.");

        // the bank comes back, and every capture completes as it always did
        issuerHangs.countDown();
        for (Thread t : captures) t.join(30_000);

        assertEquals(POOL, results.size());
        for (PaymentIntents.Result r : results) {
            assertInstanceOf(PaymentIntents.Ok.class, r, "every capture still succeeded: " + r);
            assertEquals("succeeded", ((PaymentIntents.Ok) r).status());
        }
        for (String intent : toCapture) assertEquals("succeeded", statusOf(intent));
        assertEquals("requires_capture", statusOf(untouched), "and the one nobody captured is untouched");
        System.out.println("lesson: " + POOL + " captures hung on the issuer and an unrelated read answered in " + ms + "ms");
    }

    private static String authorised(String paymentMethodId) throws Exception {
        String intent = "pi_" + UUID.randomUUID();
        var r = PaymentIntents.authorize(intent, new BigDecimal("25.00"), "EUR",
                "bulkhead", MERCHANT, paymentMethodId, T0);
        assertInstanceOf(PaymentIntents.Ok.class, r, "set-up authorisation: " + r);
        return intent;
    }

    // ------------------------------------------------------- the stand-in bank

    /** Approves anything, and then refuses to answer a capture until told to.
     *  A bank that says no is a different lesson; this one is about a bank that
     *  says nothing, which is the case that costs a caller its resources. */
    private static void issuerHandler(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        ex.getRequestBody().readAllBytes();

        if (path.endsWith("/capture")) {
            capturesArrived.countDown();
            try { issuerHangs.await(25, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            respond(ex, "{\"ok\":true}");
            return;
        }
        respond(ex, "{\"authorization\":\"stand-in\",\"approved\":true}");
    }

    private static void respond(HttpExchange ex, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private static String statusOf(String intent) throws Exception {
        try (Connection c = PayDb.open();
             var ps = c.prepareStatement("SELECT status FROM payment_intents WHERE id = ?")) {
            ps.setString(1, intent);
            try (var rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }
}
