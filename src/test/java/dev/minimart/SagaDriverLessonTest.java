package dev.minimart;

import com.sun.net.httpserver.HttpServer;
import dev.minimart.commerce.Checkout;
import dev.minimart.commerce.Orders;
import dev.minimart.commerce.Reconciler;
import dev.minimart.commerce.RemoteSteps;
import dev.minimart.commerce.SagaDriver;
import dev.minimart.core.Db;
import dev.minimart.core.Ledger;
import dev.minimart.core.Migrate;
import dev.minipay.PayApi;
import dev.minipay.PayDb;
import dev.minipay.PaymentIntents;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE THIRD PART OF THE SAGA · something that finally acts.
 *
 * SeamLessonTest ends where the estate ended: the disagreement is durable, it is
 * named, and a person has to come and fix it. minibank has never been content
 * with that internally · its cross-shard transfer departs into a clearing
 * account, relays, arrives idempotently, and refunds itself when the destination
 * is missing · so the seam between minimart and minipay was held to a lower
 * standard than the code one repository over.
 *
 * These lessons are about what it costs to close that gap honestly, and the
 * expensive half is not the repairing. It is the REFUSING. A driver that heals
 * everything it can detect is a driver that moves money on an inference drawn
 * from two systems that are, by construction, currently disagreeing, and lesson
 * 1 is the specific way that goes wrong. Lessons 2 and 3 are the two repairs
 * that survive the argument, and lessons 4 and 5 are the two properties without
 * which an automated actor on a money path should not be allowed to run at all.
 */
class SagaDriverLessonTest {

    static HttpServer pay;
    static final int PAY_PORT = 18190;
    static final String TENANT = "helix", LOC = "MAD", VARIANT = "v-mots-10mg";
    static final Instant T0 = Instant.parse("2026-04-01T09:00:00Z");
    static final Instant LATER = T0.plus(java.time.Duration.ofHours(2));

    @BeforeAll
    static void boot() throws Exception {
        Migrate.bootstrap();
        PayDb.bootstrap();
        pay = PayApi.start(PAY_PORT);
    }

    @AfterAll
    static void stop() {
        if (pay != null) pay.stop(0);
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
     * LESSON 1 · THE HAZARD. AN UNKNOWN AUTHORISATION IS NOT AN ABANDONED ONE.
     *
     * This is the lesson the driver exists to not violate, and it is the reason
     * a reporter shipped a night before an actor did.
     *
     * The tempting repair reads: the authorize step says 'unknown', an unknown
     * authorisation may be standing at the processor, minipay's void is
     * idempotent and its issuer compare-and-swaps, so voiding again cannot hurt.
     * Every clause of that is true and the conclusion is still wrong, because
     * idempotence answers "what if this runs twice" and the actual question is
     * "whose hold is this". Intent ids are DERIVED and not minted · "pi_" +
     * orderId · so the hold behind an unknown authorize is the very same hold
     * that a live order for that id is using. The second void is not a duplicate
     * of the first. It is a different and later decision, and minipay has no way
     * to see that it is a wrong one: a void arriving for a payment in
     * requires_capture is exactly what a legitimate cancellation looks like.
     *
     * The position below is reachable and not contrived. place() writes UNKNOWN
     * and then compensates inside `try { Orders.abort(...) } catch (SQLException
     * ignored) {}`, so a database that blinks in that window leaves precisely
     * this: a RESERVED order, a real hold, and a journal that says nobody knows.
     * The customer was told the checkout failed; the order is alive and shippable.
     *
     * What saves the driver is that it does not ask the journal. It asks the
     * ORDER, which is the only authority on whether this shop still wants the
     * money, and the order says reserved.
     */
    @Test
    void lesson1_an_unknown_authorize_on_a_live_order_is_never_voided() throws Exception {
        UUID orderId = UUID.randomUUID();
        assertInstanceOf(Checkout.Placed.class, Checkout.place(orderId, TENANT, 70L, VARIANT, LOC, 2, T0));

        // The answer to the authorisation is lost while the order stays live:
        // the shape place() leaves behind when its own compensation cannot run.
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE remote_steps SET state = 'unknown', detail = 'read timed out' "
                     + "WHERE order_id = ? AND action = ?")) {
            ps.setObject(1, orderId);
            ps.setString(2, RemoteSteps.AUTHORIZE);
            assertEquals(1, ps.executeUpdate());
        }
        try (Connection c = Db.open()) {
            assertEquals("reserved", orderState(c, orderId), "the order is alive and shippable");
            assertEquals(RemoteSteps.State.UNKNOWN,
                    RemoteSteps.find(c, orderId, RemoteSteps.AUTHORIZE).state());
        }

        SagaDriver.Report r = SagaDriver.run(TENANT, LATER, 100);

        // THE ASSERTION THE WHOLE CLASS IS FOR.
        assertTrue(r.taken().isEmpty(), "an unsettled step on a LIVE order is not a mandate to move money");
        assertEquals(1, r.leftAlone().size(), "and refusing is reported, not silent");

        try (Connection c = PayDb.open()) {
            assertEquals(new BigDecimal("80.00"),
                    Ledger.balance(c, PaymentIntents.holds(TENANT)).stripTrailingZeros().setScale(2),
                    "the hold the live order is going to capture is still there");
        }
        // and it really was live: the order ships, against that exact hold
        assertTrue(Checkout.ship(orderId, LATER), "the order the driver did not sabotage completes normally");
        try (Connection c = Db.open()) {
            assertEquals("fulfilled", orderState(c, orderId));
        }
        System.out.println("lesson 1: " + r.leftAlone().get(0));
    }

    /**
     * LESSON 2 · A GENUINELY ABANDONED HOLD IS RELEASED, AND THE PROOF IS LOCAL.
     *
     * The mirror of lesson 1, one field different: the order is 'aborted'. That
     * single word is what turns an unprovable repair into a provable one, and it
     * is worth being precise about why, because "the order looks dead" is not an
     * argument.
     *
     * Orders.submit claims the order id as a LEDGER TRANSACTION ID before it does
     * anything else, so the id is spent permanently and a retried checkout gets
     * AlreadyProcessed rather than an authorisation. Orders.move only acts while
     * the reservation is 'held', and aborting released it, so there is no path
     * back to a state that wants money. And the intent is named after the order,
     * so no OTHER order can be using this hold either. The retry that makes
     * lesson 1 dangerous cannot happen to this order · not "is unlikely to", but
     * is refused by a uniqueness constraint that was already there for other
     * reasons.
     *
     * So the hold is abandoned in the strong sense: nothing in this shop can ever
     * legitimately want it again. That is when releasing it is a repair rather
     * than a guess.
     */
    @Test
    void lesson2_an_abandoned_hold_is_released() throws Exception {
        UUID orderId = UUID.randomUUID();
        assertInstanceOf(Checkout.Placed.class, Checkout.place(orderId, TENANT, 71L, VARIANT, LOC, 2, T0));

        Checkout.voidSabotage = true;
        assertFalse(Checkout.cancel(orderId, LATER), "the void fails, the goods still come back");
        Checkout.voidSabotage = false;

        // the position SeamLessonTest could only ever name
        assertEquals(Reconciler.Kind.ABORTED_HOLD_STANDING,
                Reconciler.run(TENANT, 100).discrepancies().get(0).kind());

        SagaDriver.Report r = SagaDriver.run(TENANT, LATER, 100);

        assertEquals(1, r.taken().size());
        assertEquals(SagaDriver.Verdict.VOID_THE_HOLD, r.taken().get(0).verdict());
        assertTrue(r.taken().get(0).succeeded());

        try (Connection c = PayDb.open()) {
            assertEquals(0, BigDecimal.ZERO.compareTo(Ledger.balance(c, PaymentIntents.holds(TENANT))),
                    "the customer's credit is theirs again");
        }
        // AND THE ESTATE AGREES WITH ITSELF AGAIN, which is the actual deliverable
        assertTrue(Reconciler.run(TENANT, 100).agreed(),
                "the report that named the problem is what confirms it is gone");
        try (Connection c = Db.open()) {
            assertTrue(RemoteSteps.unsettled(c).isEmpty(),
                    "and the work queue is empty, so the driver will not examine this order forever");
        }
        System.out.println("lesson 2: " + r.taken().get(0));
    }

    /**
     * LESSON 3 · THE OTHER DIRECTION. A SAGA RESUMES AS WELL AS COMPENSATES.
     *
     * SeamLessonTest lesson 2 leaves the customer charged for goods still on the
     * shelf, and it is the failure the ordering inside ship() deliberately CHOSE
     * as the survivable one: capture first, so the bad case is recoverable.
     * Nothing ever recovered it.
     *
     * Rolling this back would mean a refund, which is a new movement of money.
     * Rolling it FORWARD is the completion of one that already happened, and the
     * evidence is not an inference: minipay says 'succeeded' about a payment
     * named after this order, and a succeeded capture cannot become uncaptured.
     *
     * The strongest thing about this repair is that its worst case is a no-op.
     * Orders.fulfil takes the reservation row FOR UPDATE and moves nothing unless
     * it is still 'held', so an order aborted in the meantime simply declines the
     * fulfil. The reservation row referees it · the same job it does for the
     * pooled-stock race · which means the decision does not depend on the read
     * that motivated it still being true when the write lands.
     */
    @Test
    void lesson3_a_capture_whose_local_half_failed_is_resumed() throws Exception {
        UUID orderId = UUID.randomUUID();
        assertInstanceOf(Checkout.Placed.class, Checkout.place(orderId, TENANT, 72L, VARIANT, LOC, 3, T0));

        Checkout.fulfilSabotage = true;
        assertThrows(IllegalStateException.class, () -> Checkout.ship(orderId, LATER));
        Checkout.fulfilSabotage = false;

        assertEquals(Reconciler.Kind.CAPTURED_NOT_FULFILLED,
                Reconciler.run(TENANT, 100).discrepancies().get(0).kind());

        SagaDriver.Report r = SagaDriver.run(TENANT, LATER, 100);

        assertEquals(1, r.taken().size());
        assertEquals(SagaDriver.Verdict.RESUME_THE_FULFIL, r.taken().get(0).verdict());
        assertTrue(r.taken().get(0).succeeded());

        try (Connection c = Db.open()) {
            assertEquals("fulfilled", orderState(c, orderId), "the customer who paid finally has the goods");
            assertEquals(3, Ledger.balance(c, Orders.sold(LOC, VARIANT)).intValueExact());
            assertEquals(0, Ledger.balance(c, Orders.reserved(LOC, VARIANT)).intValueExact(),
                    "and the units are no longer reserved against nothing");
            assertTrue(Ledger.sumZeroViolations(c).isEmpty(), "repaired without unbalancing the books");
        }
        assertTrue(Reconciler.run(TENANT, 100).agreed(), "the two services agree again");
        System.out.println("lesson 3: " + r.taken().get(0));
    }

    /**
     * LESSON 4 · RUNNING TWICE, AND RUNNING BESIDE ITSELF, MUST NOT ACT TWICE.
     *
     * Every scheduled repair is eventually run twice: a timer that overlaps, two
     * instances after a deploy, an operator who reruns it because the first pass
     * looked slow. If the driver's protection against that is that everything it
     * calls is idempotent, then the protection is the FAR SIDE'S, and the far
     * side cannot tell a duplicate from a later decision · which is lesson 1 all
     * over again, arriving by a different road.
     *
     * So the claim is a conditional UPDATE, the same discipline the outbox uses
     * to publish an event once. Two drivers reach it with the same verdict and
     * exactly one gets past it, because a row can only be claimed from unclaimed
     * once. Read-then-write would let both see attempts = 0 and both go.
     */
    @Test
    void lesson4_the_driver_is_safe_to_run_twice_and_to_run_beside_itself() throws Exception {
        UUID first = UUID.randomUUID();
        assertInstanceOf(Checkout.Placed.class, Checkout.place(first, TENANT, 73L, VARIANT, LOC, 1, T0));
        Checkout.voidSabotage = true;
        Checkout.cancel(first, LATER);
        Checkout.voidSabotage = false;

        // ---- twice, in sequence ----
        assertEquals(1, SagaDriver.run(TENANT, LATER, 100).taken().size());
        SagaDriver.Report again = SagaDriver.run(TENANT, LATER, 100);
        assertTrue(again.taken().isEmpty(), "the second pass finds nothing left to do");
        assertEquals(0, again.ordersExamined(), "a settled order leaves the work queue entirely");

        // The sequential pass above is reassuring and it proves LESS than it
        // looks. The repair marked the step 'ok', so the order left the work
        // queue and the second pass had nothing to find. Sequential reruns would
        // still be safe with no claim at all, which is exactly why the claim
        // cannot be tested that way: the interesting case is two drivers that
        // have BOTH already decided and neither has acted.

        // ---- eight drivers, one step, released at the same instant ----
        //
        // Every thread below has passed the reads and holds the same verdict.
        // This is the moment the conditional UPDATE exists for, and it is worth
        // provoking deterministically rather than hoping a thread pool overlaps:
        // the version of this test that raced two full passes through run()
        // passed with the guard deleted, because the two passes did not actually
        // collide. A concurrency test that only sometimes runs concurrently is a
        // test that reports a mechanism it never exercised.
        UUID second = UUID.randomUUID();
        assertInstanceOf(Checkout.Placed.class, Checkout.place(second, TENANT, 74L, VARIANT, LOC, 1, T0));
        Checkout.voidSabotage = true;
        Checkout.cancel(second, LATER);
        Checkout.voidSabotage = false;

        int drivers = 8;
        CyclicBarrier startLine = new CyclicBarrier(drivers);
        ExecutorService pool = Executors.newFixedThreadPool(drivers);
        try {
            List<Callable<Boolean>> all = new java.util.ArrayList<>();
            for (int i = 0; i < drivers; i++) {
                all.add(() -> {
                    startLine.await();
                    return RemoteSteps.claim(second, RemoteSteps.CANCEL, Checkout.intentIdFor(second),
                            LATER, SagaDriver.MAX_ATTEMPTS, SagaDriver.LEASE);
                });
            }
            int won = 0;
            for (Future<Boolean> f : pool.invokeAll(all)) if (f.get()) won++;
            assertEquals(1, won, "eight drivers decided the same repair and exactly one may perform it");
        } finally {
            pool.shutdownNow();
        }

        // ONE attempt recorded, not eight. The count is what lesson 5's ceiling
        // is measured against, so losers inflating it would silently ration a
        // step to a fraction of the retries it is supposed to get.
        try (Connection c = Db.open()) {
            assertEquals(1, RemoteSteps.attempts(c, first, RemoteSteps.CANCEL));
            assertEquals(1, RemoteSteps.attempts(c, second, RemoteSteps.CANCEL),
                    "the seven that lost left no trace on the counter");
        }
        try (Connection c = PayDb.open()) {
            assertTrue(Ledger.sumZeroViolations(c).isEmpty());
        }
        System.out.println("lesson 4: a second pass found nothing, and eight simultaneous drivers "
                + "produced exactly one claim.");
    }

    /**
     * LESSON 5 · A RETRY WITH NO CEILING IS AN OUTAGE THAT NEVER GETS REPORTED.
     *
     * The failure mode of a self-healing system is that it is always healing.
     * A step that cannot be repaired · because the far side genuinely refuses,
     * or because the disagreement is about the world rather than about a dropped
     * packet · will be attempted on every pass forever, and forever is
     * indistinguishable from working. Nobody is paged, the report says the driver
     * ran, and the hold stands for a month.
     *
     * So the driver stops, and the stopping is the part that has to be VISIBLE.
     * It is written onto the step's own detail, which is already what Reconciler
     * prints beside the discrepancy, so giving up surfaces in the register an
     * operator reads at three in the morning without anything new to subscribe to.
     */
    @Test
    void lesson5_a_step_that_exhausts_its_retries_becomes_visible_rather_than_silent() throws Exception {
        UUID orderId = UUID.randomUUID();
        assertInstanceOf(Checkout.Placed.class, Checkout.place(orderId, TENANT, 75L, VARIANT, LOC, 2, T0));
        Checkout.voidSabotage = true;                 // the void will never work, on any pass
        Checkout.cancel(orderId, LATER);

        // EXACTLY the ceiling, and not one pass more. The news that nobody is
        // coming back for this step has to be there the moment the last attempt
        // is spent: an operator should not have to wait for the next scheduled
        // run to find out, and "it becomes visible eventually" is the property
        // an unbounded retry already has.
        for (int i = 0; i < SagaDriver.MAX_ATTEMPTS; i++) SagaDriver.run(TENANT, LATER, 100);

        try (Connection c = Db.open()) {
            assertEquals(SagaDriver.MAX_ATTEMPTS, RemoteSteps.attempts(c, orderId, RemoteSteps.CANCEL),
                    "it stopped at the ceiling instead of trying on every pass forever");

            List<RemoteSteps.Step> spent = RemoteSteps.exhausted(c, SagaDriver.MAX_ATTEMPTS);
            assertEquals(1, spent.size(), "and it is enumerable as work that is now a person's");
            assertTrue(spent.get(0).detail().startsWith("exhausted after"), spent.get(0).detail());
            assertTrue(spent.get(0).detail().contains("sabotaged"),
                    "the giving-up is PREPENDED · the original diagnosis is still the useful half");
        }

        // Further passes neither retry nor restate. A ceiling that let the note
        // stutter would turn one dead step into a growing sentence.
        for (int i = 0; i < 3; i++) SagaDriver.run(TENANT, LATER, 100);
        try (Connection c = Db.open()) {
            assertEquals(SagaDriver.MAX_ATTEMPTS, RemoteSteps.attempts(c, orderId, RemoteSteps.CANCEL),
                    "a spent step is not attempted again on every later pass");
            String detail = RemoteSteps.find(c, orderId, RemoteSteps.CANCEL).detail();
            assertEquals(detail.indexOf("exhausted after"), detail.lastIndexOf("exhausted after"),
                    "and it is said once: " + detail);
        }

        // THE VISIBILITY THAT MATTERS: the operator's existing report says it,
        // without a new endpoint, a new alert, or anyone knowing the driver exists.
        Reconciler.Discrepancy d = Reconciler.run(TENANT, 100).discrepancies().get(0);
        assertEquals(Reconciler.Kind.ABORTED_HOLD_STANDING, d.kind());
        assertTrue(d.detail().contains("exhausted after"),
                "a step nothing is coming back for must not read like one that is queued: " + d.detail());

        // and the hold really is still standing · giving up is not pretending
        try (Connection c = PayDb.open()) {
            assertEquals(new BigDecimal("80.00"),
                    Ledger.balance(c, PaymentIntents.holds(TENANT)).stripTrailingZeros().setScale(2));
        }
        System.out.println("lesson 5: " + d);
    }

    /**
     * LESSON 6 · WHAT THE DRIVER REFUSES, WHICH IS MOST OF IT.
     *
     * Reconciler names nine ways the two services can disagree and the driver
     * repairs two. The other seven all have a plausible automatic fix, and the
     * plausibility is the trap: capturing a shipped-but-uncaptured order is
     * obviously right and is also the driver deciding, by itself, to charge a
     * customer. Refunding an aborted-but-captured order is obviously right and is
     * a new movement of money that this seam has no primitive for.
     *
     * A driver that acts on those is not more useful, it is less reviewable. The
     * two it does perform share a property none of the others have: the local
     * order row has reached a state that PROVES what the shop wants, and the
     * repair either moves nothing remotely at all, or moves the one thing that
     * nothing can ever legitimately want again.
     *
     * The refusals are reported rather than skipped in silence, because a driver
     * whose output is only its successes teaches an operator that anything it
     * did not mention was fine.
     */
    @Test
    void lesson6_the_positions_it_will_not_touch_are_named_and_left() throws Exception {
        // shipped here, still only authorised there · the fix is to charge someone
        UUID shipped = UUID.randomUUID();
        assertInstanceOf(Checkout.Placed.class, Checkout.place(shipped, TENANT, 76L, VARIANT, LOC, 1, T0));
        Checkout.captureSabotage = true;
        assertFalse(Checkout.ship(shipped, LATER), "the capture fails, so nothing ships");
        Checkout.captureSabotage = false;
        Orders.fulfil(shipped, LATER);               // the goods leave anyway, by another hand

        // an unreachable processor · the one thing that must produce NO action
        Checkout.payBaseUrl = "http://localhost:1";
        SagaDriver.Report dark = SagaDriver.run(TENANT, LATER, 100);
        assertTrue(dark.taken().isEmpty(), "a driver that repairs while the far side is dark is guessing");
        assertTrue(dark.leftAlone().isEmpty(), "and silence is not a refusal either · it is an unanswered question");
        assertEquals(1, dark.unreachable(), "which is counted as exactly that");
        Checkout.payBaseUrl = "http://localhost:" + PAY_PORT;

        SagaDriver.Report r = SagaDriver.run(TENANT, LATER, 100);
        assertTrue(r.taken().isEmpty(), "taking money for goods already gone is a person's signature, not a sweep");
        assertEquals(1, r.leftAlone().size());
        assertTrue(r.leftAlone().get(0).contains("fulfilled"), r.leftAlone().get(0));

        // the money is exactly where it was: still a hold, never captured
        try (Connection c = PayDb.open()) {
            assertEquals(new BigDecimal("40.00"),
                    Ledger.balance(c, PaymentIntents.holds(TENANT)).stripTrailingZeros().setScale(2),
                    "refusing moved nothing");
        }
        // and the reconciler still carries it, so refusing is not forgetting
        assertEquals(Reconciler.Kind.SHIPPED_UNCAPTURED,
                Reconciler.run(TENANT, 100).discrepancies().get(0).kind());
        System.out.println("lesson 6: " + r.leftAlone().get(0));
    }

    // ------------------------------------------------------------------ helpers

    private static String orderState(Connection c, UUID id) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT state FROM orders WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getString(1); }
        }
    }
}
