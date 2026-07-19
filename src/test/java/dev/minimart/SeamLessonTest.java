package dev.minimart;

import com.sun.net.httpserver.HttpServer;
import dev.minimart.commerce.Checkout;
import dev.minimart.commerce.Orders;
import dev.minimart.commerce.Reconciler;
import dev.minimart.commerce.RemoteSteps;
import dev.minimart.commerce.ReservationSweeper;
import dev.minimart.core.Db;
import dev.minimart.core.Ledger;
import dev.minimart.core.Migrate;
import dev.minipay.PayApi;
import dev.minipay.PayDb;
import dev.minipay.PaymentIntents;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE SEAM · where every per-service invariant stops being enough.
 *
 * Inside minimart, and inside minipay, the discipline is genuinely good: the
 * outbox commits with the money, transaction ids make redelivery a no-op, the
 * reservation row referees the race, the sagas compensate. Every one of those
 * mechanisms lives inside ONE database and one commit.
 *
 * The calls BETWEEN the two services had none of it. Each one was an HTTP
 * request whose answer became a boolean in a local variable, with no record that
 * a remote step was ever attempted and nothing that ever revisited it. So money
 * could be stranded at one service while both services' own audits passed,
 * because each ledger balances internally.
 *
 * That is exactly the trap RaceLessonTest names one database over: "the books
 * still balance, which is the trap". These lessons carry it across the network.
 * Lesson 3 is the one that makes the argument.
 */
class SeamLessonTest {

    static HttpServer pay;
    /** Forwards to minipay and then loses the response, so a request that
     *  genuinely landed still looks like a failure to the caller. */
    static HttpServer lossy;
    static final int PAY_PORT = 18180, LOSSY_PORT = 18181;
    static final String TENANT = "helix", LOC = "MAD", VARIANT = "v-mots-10mg";
    static final Instant T0 = Instant.parse("2026-03-01T09:00:00Z");
    static final Instant LATER = T0.plus(java.time.Duration.ofHours(2));

    @BeforeAll
    static void boot() throws Exception {
        Migrate.bootstrap();
        PayDb.bootstrap();
        pay = PayApi.start(PAY_PORT);
        lossy = startLossyProxy(LOSSY_PORT, PAY_PORT);
    }

    @AfterAll
    static void stop() {
        if (pay != null) pay.stop(0);
        if (lossy != null) lossy.stop(0);
    }

    @BeforeEach
    void reset() throws Exception {
        Checkout.payBaseUrl = "http://localhost:" + PAY_PORT;
        Checkout.captureSabotage = false;
        Checkout.voidSabotage = false;
        Checkout.fulfilSabotage = false;
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE remote_steps, outbox, handled_events, reservations, orders, entries, "
                     + "transactions, accounts, variants, tenants RESTART IDENTITY CASCADE");
            st.execute("INSERT INTO tenants(slug) VALUES ('" + TENANT + "')");
            st.execute("INSERT INTO variants(id, tenant, title, price) VALUES ('" + VARIANT
                     + "','" + TENANT + "','MOTS-c 10mg', 40.00)");
        }
        try (Connection c = PayDb.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE idempotency_keys, payment_intents, entries, transactions, accounts RESTART IDENTITY CASCADE");
        }
        Orders.receiveStock(TENANT, LOC, VARIANT, 100, T0);
    }

    @AfterEach
    void clearSabotage() {
        Checkout.captureSabotage = false;
        Checkout.voidSabotage = false;
        Checkout.fulfilSabotage = false;
    }

    /**
     * LESSON 1 · A FAILED VOID IS NOT A CANCELLED ORDER.
     *
     * cancel() called settle(orderId, "cancel", at) as a bare statement and
     * threw the boolean away, then returned a hardcoded true. So a void that
     * failed was indistinguishable from one that worked: the goods went back on
     * the shelf, a real authorisation stayed standing at the issuer against an
     * order that no longer exists, and no sweeper, retry or audit ever revisited
     * it. The customer's credit is consumed by a purchase nobody made.
     */
    @Test
    void lesson1_a_failed_void_is_named_by_the_reconciler() throws Exception {
        UUID orderId = UUID.randomUUID();
        assertInstanceOf(Checkout.Placed.class, Checkout.place(orderId, TENANT, 60L, VARIANT, LOC, 2, T0));

        Checkout.voidSabotage = true;
        boolean voided = Checkout.cancel(orderId, LATER);

        // FIRST: the answer is now an answer. It used to be an unconditional true.
        assertFalse(voided, "cancel() reports the void it actually got, not the one it hoped for");

        // the goods still come back · stock may not be hostage to the processor
        try (Connection c = Db.open()) {
            assertEquals(100, Ledger.balance(c, Orders.onHand(LOC, VARIANT)).intValueExact());
            assertEquals("aborted", orderState(c, orderId));
            // and the attempt is on the record, which is what makes it actionable
            RemoteSteps.Step step = RemoteSteps.find(c, orderId, RemoteSteps.CANCEL);
            assertNotNull(step, "a remote step that was attempted leaves a row");
            assertEquals(RemoteSteps.State.FAILED, step.state());
        }

        // the hold really is still standing over there
        try (Connection c = PayDb.open()) {
            assertEquals(new BigDecimal("80.00"),
                    Ledger.balance(c, PaymentIntents.holds(TENANT)).stripTrailingZeros().setScale(2),
                    "minipay is still holding money for an order minimart has cancelled");
        }

        Reconciler.Report r = Reconciler.run(TENANT, 100);
        assertFalse(r.agreed(), "the two services do not agree, and the report says so");
        assertEquals(1, r.discrepancies().size());
        Reconciler.Discrepancy d = r.discrepancies().get(0);
        assertEquals(Reconciler.Kind.ABORTED_HOLD_STANDING, d.kind());
        assertEquals(orderId, d.orderId());
        assertEquals("pi_" + orderId, d.intentId(), "the id chain is derived, never looked up");
        assertNotNull(d.detail(), "and the journal says WHY, which is what an operator needs");
        System.out.println("lesson 1: " + d);
    }

    /**
     * LESSON 2 · THE CAPTURE LANDED AND THE LOCAL HALF DID NOT.
     *
     * ship() captured the money remotely and then committed the local fulfil in
     * a SEPARATE transaction, because nothing can make an HTTP call atomic with
     * a commit. That ordering is right · the survivable failure is "charged and
     * not shipped", never "shipped and not charged". What was missing is that
     * the survivable failure was not being survived: nothing recorded that the
     * money had moved while the order had not, so the customer was charged for
     * goods still sitting on the shelf and no part of the system knew.
     *
     * Reaching this needed a new fault seam. captureSabotage short-circuits
     * BEFORE the network, so it can only produce the safe failure.
     */
    @Test
    void lesson2_a_capture_whose_local_half_fails_is_detected() throws Exception {
        UUID orderId = UUID.randomUUID();
        assertInstanceOf(Checkout.Placed.class, Checkout.place(orderId, TENANT, 61L, VARIANT, LOC, 3, T0));

        Checkout.fulfilSabotage = true;
        // The failure keeps travelling. A caller told "shipped" here would be
        // the second lie stacked on the first.
        assertThrows(IllegalStateException.class, () -> Checkout.ship(orderId, LATER));

        try (Connection c = Db.open()) {
            assertEquals("reserved", orderState(c, orderId), "the goods never moved");
            RemoteSteps.Step capture = RemoteSteps.find(c, orderId, RemoteSteps.CAPTURE);
            RemoteSteps.Step fulfil = RemoteSteps.find(c, orderId, RemoteSteps.FULFIL);
            assertNotNull(capture, "the capture was journalled");
            assertNotNull(fulfil, "the local half that failed AFTER the money moved is on the record too, "
                    + "which is the whole difference between a recoverable failure and a silent one");
            assertEquals(RemoteSteps.State.OK, capture.state(), "the money genuinely moved");
            assertEquals(RemoteSteps.State.FAILED, fulfil.state(), "and the local half genuinely did not");
        }
        try (Connection c = PayDb.open()) {
            assertEquals(0, new BigDecimal("120.00").compareTo(
                            Ledger.balance(c, dev.minipay.Settlements.receivable(TENANT))),
                    "the customer has been charged");
        }

        Reconciler.Report r = Reconciler.run(TENANT, 100);
        assertEquals(1, r.discrepancies().size());
        assertEquals(Reconciler.Kind.CAPTURED_NOT_FULFILLED, r.discrepancies().get(0).kind());
        System.out.println("lesson 2: " + r.discrepancies().get(0));
    }

    /**
     * LESSON 3 · THE HEADLINE. EVERY AUDIT ON BOTH SIDES IS GREEN AND THE MONEY
     * IS STILL IN THE WRONG PLACE.
     *
     * Two orders, two different ways of going wrong: one cancelled here while
     * still authorised there, one captured there while still reserved here. Then
     * every audit either service owns is run, and every one of them passes.
     *
     * They pass HONESTLY. minimart's books balance because only stock moved and
     * it moved correctly. minipay's books balance because its money moved
     * correctly too. Sum-zero, cache drift and the reserved-stock audit are all
     * clean; so are minipay's. Each service is internally consistent and each is
     * telling the truth about itself.
     *
     * NEITHER OF THEM CAN SEE THE CUSTOMER. That is not a bug in any of the six
     * audits, it is the shape of the question they ask: every one of them is
     * scoped to one database, and the failure lives in the gap between two. Only
     * something that asks both services about the same order can name it, which
     * is the entire argument for the reconciler existing.
     */
    @Test
    void lesson3_both_services_audits_pass_while_the_reconciler_disagrees() throws Exception {
        UUID stranded = UUID.randomUUID();
        assertInstanceOf(Checkout.Placed.class, Checkout.place(stranded, TENANT, 62L, VARIANT, LOC, 2, T0));
        Checkout.voidSabotage = true;
        Checkout.cancel(stranded, LATER);                    // goods back, hold still standing
        Checkout.voidSabotage = false;

        UUID charged = UUID.randomUUID();
        assertInstanceOf(Checkout.Placed.class, Checkout.place(charged, TENANT, 63L, VARIANT, LOC, 1, T0));
        Checkout.fulfilSabotage = true;
        assertThrows(IllegalStateException.class, () -> Checkout.ship(charged, LATER));
        Checkout.fulfilSabotage = false;

        // ---- minimart's three audits, all of them, all green ----
        try (Connection c = Db.open()) {
            assertTrue(Ledger.sumZeroViolations(c).isEmpty(), "the store's books balance, which is the trap");
            assertTrue(Ledger.driftedAccounts(c).isEmpty(), "no cache drift either");
            assertTrue(ReservationSweeper.reservedMismatches(c).isEmpty(),
                    "even the audit built for the pooled-stock race is satisfied");
        }
        // ---- minipay's, likewise ----
        try (Connection c = PayDb.open()) {
            assertTrue(Ledger.sumZeroViolations(c).isEmpty(), "the processor's books balance too");
            assertTrue(Ledger.driftedAccounts(c).isEmpty());
        }

        // ---- and yet ----
        Reconciler.Report r = Reconciler.run(TENANT, 100);
        assertFalse(r.agreed(), "six green audits, and the two services still disagree");
        assertEquals(2, r.discrepancies().size(), "both strandings are named, not just the loudest");
        List<Reconciler.Kind> kinds = r.discrepancies().stream().map(Reconciler.Discrepancy::kind).toList();
        assertTrue(kinds.contains(Reconciler.Kind.ABORTED_HOLD_STANDING));
        assertTrue(kinds.contains(Reconciler.Kind.CAPTURED_NOT_FULFILLED));

        // the ONE thing a reconciler must never do
        try (Connection c = PayDb.open()) {
            assertEquals(new BigDecimal("80.00"),
                    Ledger.balance(c, PaymentIntents.holds(TENANT)).stripTrailingZeros().setScale(2),
                    "reporting moved nothing · a reconciler that silently moves money is worse than none");
        }
        r.discrepancies().forEach(d -> System.out.println("lesson 3: " + d));
        System.out.println("lesson 3: every per-service audit passed. Per-service invariants are not enough.");
    }

    /**
     * LESSON 4 · A TIMEOUT IS NOT A DECLINE.
     *
     * place() caught EVERY exception from the authorisation call and treated it
     * as "no authorisation exists", which is true for a refused connection and
     * false for a lost response. The request had landed. minipay was holding a
     * real customer's money, minimart put the goods back, and the abandoned hold
     * was never spoken of again by anything.
     *
     * The compensation is unchanged and correct: the stock still comes back,
     * because it cannot be held hostage to an unanswered question. What changes
     * is that the question is now written down as UNKNOWN rather than filed as a
     * no, and the reconciler is what eventually answers it.
     */
    @Test
    void lesson4_a_lost_response_is_recorded_as_unknown_not_as_no() throws Exception {
        Checkout.payBaseUrl = "http://localhost:" + LOSSY_PORT;
        UUID orderId = UUID.randomUUID();

        Checkout.Result r = Checkout.place(orderId, TENANT, 64L, VARIANT, LOC, 2, T0);
        assertInstanceOf(Checkout.Rejected.class, r, "the caller is told it did not work, which is honest");

        Checkout.payBaseUrl = "http://localhost:" + PAY_PORT;      // the network comes back

        try (Connection c = Db.open()) {
            assertEquals(100, Ledger.balance(c, Orders.onHand(LOC, VARIANT)).intValueExact(),
                    "the goods came back, exactly as before");
            RemoteSteps.Step step = RemoteSteps.find(c, orderId, RemoteSteps.AUTHORIZE);
            assertEquals(RemoteSteps.State.UNKNOWN, step.state(),
                    "not FAILED · the request may well have been served, and it was");
        }

        // it was. minipay authorised a real customer.
        try (Connection c = PayDb.open()) {
            assertEquals(1, count(c, "SELECT COUNT(*) FROM payment_intents WHERE id = 'pi_" + orderId + "'"),
                    "the authorisation the old code declared nonexistent");
        }

        Reconciler.Report r2 = Reconciler.run(TENANT, 100);
        assertEquals(1, r2.discrepancies().size());
        assertEquals(Reconciler.Kind.ABORTED_HOLD_STANDING, r2.discrepancies().get(0).kind());
        System.out.println("lesson 4: " + r2.discrepancies().get(0));
    }

    /**
     * LESSON 5 · A RECONCILER THAT ALWAYS COMPLAINS IS A RECONCILER NOBODY READS.
     *
     * Three things have to be true for the report to be worth acting on: it is
     * silent when the two services agree, it does not mistake an unreachable
     * processor for stranded money, and it can see a payment that has no order
     * at all · the direction that starting from minimart's own rows structurally
     * cannot reach.
     */
    @Test
    void lesson5_agreement_is_silent_and_absence_is_not_a_verdict() throws Exception {
        UUID shipped = UUID.randomUUID();
        assertInstanceOf(Checkout.Placed.class, Checkout.place(shipped, TENANT, 65L, VARIANT, LOC, 1, T0));
        assertTrue(Checkout.ship(shipped, LATER));

        UUID cancelled = UUID.randomUUID();
        assertInstanceOf(Checkout.Placed.class, Checkout.place(cancelled, TENANT, 66L, VARIANT, LOC, 1, T0));
        assertTrue(Checkout.cancel(cancelled, LATER), "a void that worked reports that it worked");

        assertTrue(Reconciler.run(TENANT, 100).agreed(), "healthy traffic produces an empty report");

        // A PROCESSOR THAT IS DOWN IS NOT A SHOP FULL OF STRANDED MONEY. If an
        // unanswered GET read as "the payment is absent", every aborted order
        // would clear and every live one would be flagged, on the day the
        // operator most needs the report to mean something.
        Checkout.payBaseUrl = "http://localhost:1";
        Reconciler.Report dark = Reconciler.run(TENANT, 100);
        assertTrue(dark.discrepancies().isEmpty(), "silence from minipay is not evidence about minipay");
        assertTrue(dark.unreachable() > 0, "but it is reported, so nobody reads the silence as health");
        assertFalse(dark.agreed(), "and it is emphatically not agreement");
        Checkout.payBaseUrl = "http://localhost:" + PAY_PORT;

        // THE OTHER DIRECTION. A payment minipay holds for an order minimart has
        // no row for, which is what a lost authorisation looks like from the far
        // side and is invisible to any check that starts from the order table.
        UUID ghost = UUID.randomUUID();
        PaymentIntents.authorize("pi_" + ghost, new BigDecimal("40.00"), "SIMEUR",
                "cust_67", TENANT, T0);
        Reconciler.Report orphaned = Reconciler.run(TENANT, 100);
        assertEquals(1, orphaned.discrepancies().size());
        assertEquals(Reconciler.Kind.ORPHAN_INTENT, orphaned.discrepancies().get(0).kind());
        assertEquals(ghost, orphaned.discrepancies().get(0).orderId());
        System.out.println("lesson 5: agreement is silent, an unreachable processor is reported as unreachable, "
                + "and " + orphaned.discrepancies().get(0));
    }

    /** LESSON 6 · the endpoint, because an audit nobody can call is a library. */
    @Test
    void lesson6_the_report_is_reachable_over_http() throws Exception {
        HttpServer mart = dev.minimart.http.MartApi.start(18182);
        try {
            UUID orderId = UUID.randomUUID();
            assertInstanceOf(Checkout.Placed.class, Checkout.place(orderId, TENANT, 68L, VARIANT, LOC, 1, T0));
            Checkout.voidSabotage = true;
            Checkout.cancel(orderId, LATER);

            HttpResponse<String> res = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:18182/api/reconcile?tenant=" + TENANT))
                            .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, res.statusCode());
            assertTrue(res.body().contains("ABORTED_HOLD_STANDING"), res.body());
            assertTrue(res.body().contains("\"agreed\":false"), res.body());

            // /api/audit, on the same shop, at the same moment, says everything
            // is fine. It is not wrong · it is answering a smaller question.
            HttpResponse<String> local = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:18182/api/audit")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(local.body().contains("\"healthy\":true"),
                    "the local audit is green while money is stranded: " + local.body());
            System.out.println("lesson 6: /api/audit says healthy:true and /api/reconcile says agreed:false, "
                    + "at the same instant, about the same shop");
        } finally {
            mart.stop(0);
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A proxy that does the work and loses the receipt.
     *
     * It forwards the request to minipay verbatim, waits for minipay to finish,
     * and then closes the connection without answering. The caller sees an IO
     * failure on a request that fully succeeded, which is the one shape a fault
     * seam on THIS side of the network cannot produce and the only shape that
     * makes an unanswered call dangerous.
     */
    private static HttpServer startLossyProxy(int port, int upstream) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.createContext("/", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            try {
                var b = HttpRequest.newBuilder(
                                URI.create("http://localhost:" + upstream + ex.getRequestURI().getPath()))
                        .header("Content-Type", "application/json");
                String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
                if (key != null) b.header("Idempotency-Key", key);
                HttpClient.newHttpClient().send(
                        b.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                        HttpResponse.BodyHandlers.ofString());
            } catch (Exception ignored) {
                // the upstream call is the point; its answer is what we discard
            }
            ex.close();                       // no status, no body: the response is lost
        });
        s.start();
        return s;
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
}
