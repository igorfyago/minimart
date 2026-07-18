package dev.minimart;

import dev.minimart.commerce.Orders;
import dev.minimart.commerce.Replenishment;
import dev.minimart.core.Db;
import dev.minimart.core.EventRuntime;
import dev.minimart.core.Ledger;
import dev.minimart.core.Migrate;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE POISON MESSAGE · the failure mode a queue has and a request does not.
 *
 * A consumer reading a partition in order can be stopped completely by ONE
 * event it cannot handle, and every obvious response is wrong. Committing the
 * offset loses it silently. Refusing to commit means the partition never
 * advances. Retrying in place spins while the lag climbs and nothing else in
 * that partition is served.
 *
 * This is the billing defect one layer down, where a single unfulfillable
 * subscription ended the whole renewal pass, and it wants the same answer: give
 * every item its own failure domain, and put a failure that cannot be retried
 * somewhere a human will look rather than in front of the queue.
 *
 * The consumer under test does real work. It restocks a warehouse, so a
 * double-application costs a pallet rather than a wrong number on a chart,
 * which is the only sort of idempotency claim worth making. And the whole test
 * runs without a broker, because whether a failed event should be retried or
 * buried is a business decision, not a Kafka one.
 */
class PoisonMessageLessonTest {

    static final String TENANT = "helix", LOC = "MAD";
    static final String GOOD = "v-good-30", POISON = "v-poison-30";
    static final Instant T0 = Instant.parse("2026-12-01T00:00:00Z");

    @BeforeAll
    static void migrate() throws Exception { Migrate.bootstrap(); }

    @BeforeEach
    void reset() throws Exception {
        Replenishment.refuseVariant = null;
        Replenishment.threshold = 20;
        Replenishment.reorderQty = 100;
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE dead_letters, event_retries, outbox, handled_events, dunning_attempts, invoices, subscriptions, reservations, orders, entries, transactions, accounts, variants, tenants RESTART IDENTITY CASCADE");
            st.execute("INSERT INTO tenants(slug) VALUES ('" + TENANT + "')");
            st.execute("INSERT INTO variants(id, tenant, title, price) VALUES ('" + GOOD + "','" + TENANT + "','Good', 20.00)");
            st.execute("INSERT INTO variants(id, tenant, title, price) VALUES ('" + POISON + "','" + TENANT + "','Poison', 20.00)");
        }
        // just above the reorder threshold, so any sale drops it below
        Orders.receiveStock(TENANT, LOC, GOOD, 21, T0);
        Orders.receiveStock(TENANT, LOC, POISON, 21, T0);
    }

    /**
     * LESSON 1 · THE SAME EVENT, DELIVERED REPEATEDLY, ORDERS ONE PALLET.
     *
     * At-least-once delivery is the only guarantee worth having, because the
     * alternative loses events silently. The price is that duplicates must be
     * harmless, and this lesson shows there are TWO independent gates, which
     * matters because they protect against different things.
     *
     * The inner gate is the derived transaction id inside receiveStock: an
     * identical repeat at the same business instant is already a no-op, without
     * the runtime being involved at all. But a redelivery does not necessarily
     * arrive at the same business instant, and the moment the clock differs the
     * derived id differs and the inner gate opens. THAT is what the event claim
     * is for. Neither gate is redundant, and the second half of this lesson is
     * the case that proves it.
     */
    @Test
    void lesson1_a_redelivered_event_restocks_exactly_once() throws Exception {
        // a top-up small enough to leave the shelf still under the threshold,
        // so the threshold check cannot be what absorbs the second delivery.
        // Otherwise this lesson would pass without the gates existing at all.
        Replenishment.threshold = 1000;
        Replenishment.reorderQty = 2;

        UUID order = UUID.randomUUID();
        assertInstanceOf(Orders.Ok.class, Orders.submit(order, TENANT, 1L, GOOD, LOC, 5, T0, false));
        String key = "order.placed:" + order;

        // the inner gate: an exact repeat is already harmless
        long before = onHand(GOOD);
        Replenishment.onOrderPlaced(key, payloadFor(key, GOOD), T0);
        Replenishment.onOrderPlaced(key, payloadFor(key, GOOD), T0);
        assertEquals(before + 2, onHand(GOOD),
                "the derived transaction id absorbs an identical repeat on its own");

        // but a redelivery an hour later derives a DIFFERENT id, and the inner
        // gate does nothing about it
        Replenishment.onOrderPlaced(key, payloadFor(key, GOOD), T0.plus(Duration.ofHours(1)));
        assertEquals(before + 4, onHand(GOOD),
                "a redelivery at a different business time orders AGAIN: the inner gate is not enough");

        // the outer gate closes that hole. Five deliveries, spread over time.
        reset();
        Replenishment.threshold = 1000;
        Replenishment.reorderQty = 2;
        UUID order2 = UUID.randomUUID();
        Orders.submit(order2, TENANT, 2L, GOOD, LOC, 5, T0, false);
        String key2 = "order.placed:" + order2;
        EventRuntime runtime = runtime(Replenishment.CONSUMER);

        long start = onHand(GOOD);
        for (int i = 0; i < 5; i++) {
            runtime.apply(key2, payloadFor(key2, GOOD), T0.plus(Duration.ofHours(i)));
        }

        assertEquals(start + 2, onHand(GOOD),
                "five deliveries across five hours, ONE top-up");
        System.out.println("lesson 1: 5 deliveries spread over time -> stock rose by exactly "
                + Replenishment.reorderQty + " units");
    }

    /**
     * LESSON 2 · ONE UNHANDLEABLE EVENT DOES NOT STOP THE ONES BEHIND IT.
     *
     * The whole point. After a bounded number of attempts the poison event is
     * buried and the queue keeps moving, which is the difference between a bad
     * record and an outage. A consumer that stopped on it would never reach the
     * healthy event at all.
     */
    @Test
    void lesson2_a_poison_event_is_buried_and_the_queue_keeps_moving() throws Exception {
        Replenishment.refuseVariant = POISON;
        EventRuntime runtime = runtime(Replenishment.CONSUMER);

        UUID poisoned = UUID.randomUUID();
        Orders.submit(poisoned, TENANT, 3L, POISON, LOC, 5, T0, false);
        String poisonKey = "order.placed:" + poisoned;

        EventRuntime.Result last = null;
        for (int i = 0; i < EventRuntime.DEFAULT_MAX_ATTEMPTS; i++) {
            last = runtime.apply(poisonKey, payloadFor(poisonKey, POISON), T0);
        }
        assertEquals(EventRuntime.Result.BURIED, last, "the attempt budget is bounded, so it ends");
        assertEquals(1, EventRuntime.deadLetterCount(Replenishment.CONSUMER));
        assertEquals(0, EventRuntime.pendingRetryCount(Replenishment.CONSUMER), "and it left the retry queue");

        // a further delivery of the buried event does nothing at all: it is a
        // terminal state a human resolves, not one the system grinds against
        assertEquals(EventRuntime.Result.DUPLICATE, runtime.apply(poisonKey, payloadFor(poisonKey, POISON), T0),
                "a buried event stops being offered to the handler");

        // and the event behind it is served completely normally
        UUID healthy = UUID.randomUUID();
        Orders.submit(healthy, TENANT, 4L, GOOD, LOC, 5, T0, false);
        String healthyKey = "order.placed:" + healthy;
        long before = onHand(GOOD);
        assertEquals(EventRuntime.Result.HANDLED, runtime.apply(healthyKey, payloadFor(healthyKey, GOOD), T0));

        assertEquals(before + Replenishment.reorderQty, onHand(GOOD),
                "the healthy event queued behind the poison one was handled");
        System.out.println("lesson 2: buried after " + EventRuntime.DEFAULT_MAX_ATTEMPTS
                + " attempts, and the event behind it was still served");
    }

    /**
     * LESSON 3 · A FAILURE THAT MIGHT CLEAR IS RETRIED, NOT BURIED.
     *
     * The counterpart to lesson 2, and the reason an attempt budget exists at
     * all rather than burying on the first failure. A supplier briefly
     * unreachable is not a poison event, and treating it as one turns a blip
     * into a lost order. Retries run in business time at a tick boundary, so a
     * compressed run watches the ladder pass in milliseconds.
     */
    @Test
    void lesson3_a_transient_failure_recovers_with_nobody_intervening() throws Exception {
        Replenishment.refuseVariant = POISON;
        EventRuntime runtime = runtime(Replenishment.CONSUMER);

        UUID order = UUID.randomUUID();
        Orders.submit(order, TENANT, 5L, POISON, LOC, 5, T0, false);
        String key = "order.placed:" + order;

        assertEquals(EventRuntime.Result.RETRY, runtime.apply(key, payloadFor(key, POISON), T0));
        assertEquals(1, EventRuntime.pendingRetryCount(Replenishment.CONSUMER), "waiting, not dead");
        assertEquals(0, EventRuntime.deadLetterCount(Replenishment.CONSUMER));

        // the supplier comes back before the budget is spent
        Replenishment.refuseVariant = null;
        long before = onHand(POISON);
        int recovered = runtime.retryPending(T0.plus(Duration.ofHours(1)));

        assertEquals(1, recovered, "it succeeded on retry, with nobody intervening");
        assertEquals(before + Replenishment.reorderQty, onHand(POISON), "and the effect finally happened");
        assertEquals(0, EventRuntime.pendingRetryCount(Replenishment.CONSUMER), "the queue drained");
        assertEquals(0, EventRuntime.deadLetterCount(Replenishment.CONSUMER), "and nothing was buried");
        System.out.println("lesson 3: a transient failure was retried and recovered on its own");
    }

    /**
     * LESSON 4 · A DEAD LETTER KEEPS ENOUGH TO BE FIXED AND REPLAYED.
     *
     * Why the payload is stored rather than logged. By the time somebody looks,
     * the broker's retention window may have closed and the offset moved on, so
     * an event that cannot be replayed from the dead letter itself cannot be
     * replayed at all.
     */
    @Test
    void lesson4_a_buried_event_can_be_fixed_and_replayed_exactly_once() throws Exception {
        Replenishment.refuseVariant = POISON;
        EventRuntime runtime = runtime(Replenishment.CONSUMER);

        UUID order = UUID.randomUUID();
        Orders.submit(order, TENANT, 6L, POISON, LOC, 5, T0, false);
        String key = "order.placed:" + order;
        for (int i = 0; i < EventRuntime.DEFAULT_MAX_ATTEMPTS; i++) {
            runtime.apply(key, payloadFor(key, POISON), T0);
        }
        assertEquals(1, EventRuntime.deadLetterCount(Replenishment.CONSUMER));

        // it kept the payload AND why it died
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT payload, last_error, attempts FROM dead_letters WHERE event_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getString(1).contains(POISON), "the payload is intact, so it can be replayed");
                assertTrue(rs.getString(2).contains("refuses"), "and it records why it died: " + rs.getString(2));
                assertEquals(EventRuntime.DEFAULT_MAX_ATTEMPTS, rs.getInt(3));
            }
        }

        // fix the cause, replay
        Replenishment.refuseVariant = null;
        long before = onHand(POISON);
        assertTrue(runtime.replayDeadLetter(key, T0.plus(Duration.ofDays(1))), "it replays once the cause is fixed");
        assertEquals(before + Replenishment.reorderQty, onHand(POISON), "and the effect finally lands");
        assertEquals(0, EventRuntime.deadLetterCount(Replenishment.CONSUMER), "it stops being outstanding");

        // and replaying again does nothing: the claim still stands
        assertFalse(runtime.replayDeadLetter(key, T0.plus(Duration.ofDays(2))),
                "already replayed, so there is nothing left to replay");
        assertEquals(before + Replenishment.reorderQty, onHand(POISON), "and certainly no second pallet");
        System.out.println("lesson 4: dead letter kept payload and error, replayed once, and only once");
    }

    /**
     * LESSON 5 · TWO CONSUMERS OF ONE EVENT SUCCEED AND FAIL INDEPENDENTLY.
     *
     * The claim is keyed by (event, CONSUMER), not by event alone. If it were
     * keyed by event, whichever consumer reached it first would mark it handled
     * for everybody and the others would silently skip work they never did.
     * That bug is invisible from the outside: nothing errors, lag looks
     * healthy, and the second consumer simply has a hole in it.
     */
    @Test
    void lesson5_one_consumer_failing_does_not_mark_the_event_done_for_another() throws Exception {
        Replenishment.refuseVariant = POISON;
        UUID order = UUID.randomUUID();
        Orders.submit(order, TENANT, 7L, POISON, LOC, 5, T0, false);
        String key = "order.placed:" + order;
        String payload = payloadFor(key, POISON);

        EventRuntime a = new EventRuntime(Orders.TOPIC_ORDERS, "consumer-a", Replenishment::onOrderPlaced);
        AtomicInteger seenByB = new AtomicInteger();
        EventRuntime b = new EventRuntime(Orders.TOPIC_ORDERS, "consumer-b",
                (k, p, at) -> seenByB.incrementAndGet());

        assertEquals(EventRuntime.Result.RETRY, a.apply(key, payload, T0), "A cannot handle it");
        assertEquals(EventRuntime.Result.HANDLED, b.apply(key, payload, T0), "B can, and still gets the chance");

        assertEquals(1, seenByB.get(), "B saw the event A choked on");
        assertEquals(1, handledBy("consumer-b"), "and claimed it under its OWN name");
        assertEquals(0, handledBy("consumer-a"), "while A has claimed nothing, because A handled nothing");
        assertEquals(1, EventRuntime.pendingRetryCount("consumer-a"), "A still owes this event");
        assertEquals(0, EventRuntime.pendingRetryCount("consumer-b"), "B owes nothing");
        System.out.println("lesson 5: same event, A failed and B succeeded, tracked separately");
    }

    // ------------------------------------------------------------------ helpers

    private static EventRuntime runtime(String group) {
        return new EventRuntime(Orders.TOPIC_ORDERS, group, Replenishment::onOrderPlaced);
    }

    private static String payloadFor(String eventKey, String variant) {
        String orderId = eventKey.substring(eventKey.indexOf(':') + 1);
        return "{\"type\":\"order.placed\",\"eventKey\":\"" + eventKey + "\",\"orderId\":\"" + orderId
                + "\",\"tenant\":\"" + TENANT + "\",\"customer\":1,\"variant\":\"" + variant
                + "\",\"location\":\"" + LOC + "\",\"qty\":5,\"amount\":\"100.00\",\"at\":\"" + T0 + "\"}";
    }

    private static long onHand(String variant) throws Exception {
        try (Connection c = Db.open()) {
            return Ledger.balance(c, Orders.onHand(LOC, variant)).longValue();
        }
    }

    private static long handledBy(String consumer) throws Exception {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM handled_events WHERE consumer = ?")) {
            ps.setString(1, consumer);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }
}
