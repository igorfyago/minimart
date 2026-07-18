package dev.minimart;

import dev.minimart.commerce.Orders;
import dev.minimart.commerce.ReservationSweeper;
import dev.minimart.core.Db;
import dev.minimart.core.Ledger;
import dev.minimart.core.Migrate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SLICE 2 · THE RACE.
 *
 * The sweeper and the shipment can reach the same reservation at the same
 * moment. Because the reserved stock account is pooled, the ledger's own audits
 * cannot catch a mistake here: sum-zero and cache-drift both pass while the
 * warehouse quietly goes wrong. These lessons exist to prove the reservation
 * row itself is the referee, and to add the third audit that would notice.
 */
class RaceLessonTest {

    static final String TENANT = "helix";
    static final String LOC = "MAD";
    static final String VARIANT = "v-mots-10mg";
    static final Instant T0 = Instant.parse("2026-03-01T09:00:00Z");
    static final Instant LATER = T0.plus(java.time.Duration.ofHours(2));   // past the 30 minute TTL

    @BeforeAll
    static void migrate() { Migrate.run(); }

    @BeforeEach
    void reset() throws Exception {
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE reservations, orders, entries, transactions, accounts, variants, tenants RESTART IDENTITY CASCADE");
            st.execute("INSERT INTO tenants(slug) VALUES ('" + TENANT + "')");
            st.execute("INSERT INTO variants(id, tenant, title, price) VALUES ('" + VARIANT + "','" + TENANT + "','MOTS-c 10mg', 40.00)");
        }
    }

    /** LESSON 1 · an abandoned cart gives its stock back, on the business clock. */
    @Test
    void lesson1_expired_reservations_return_their_stock() throws Exception {
        Orders.receiveStock(TENANT, LOC, VARIANT, 10, T0);
        Orders.fundWallet(TENANT, 1L, new BigDecimal("500.00"), T0);
        UUID orderId = UUID.randomUUID();
        assertInstanceOf(Orders.Ok.class, Orders.submit(orderId, TENANT, 1L, VARIANT, LOC, 4, T0));

        // still inside the TTL: the sweeper must not touch it
        assertEquals(0, ReservationSweeper.sweepOnce(T0.plusSeconds(60), 100));
        try (Connection c = Db.open()) {
            assertEquals(4, Ledger.balance(c, Orders.reserved(LOC, VARIANT)).intValueExact());
        }

        // past the deadline: released
        assertEquals(1, ReservationSweeper.sweepOnce(LATER, 100));
        try (Connection c = Db.open()) {
            assertEquals(10, Ledger.balance(c, Orders.onHand(LOC, VARIANT)).intValueExact(), "back on the shelf");
            assertEquals(0, Ledger.balance(c, Orders.reserved(LOC, VARIANT)).intValueExact());
            assertEquals("aborted", orderState(c, orderId));
            assertTrue(ReservationSweeper.reservedMismatches(c).isEmpty());
        }
        System.out.println("lesson 1: sweeper honours the business clock · early no-op, late release");
    }

    /** LESSON 2 · THE HEADLINE. Sweeper and shipment reach the same reservation at once. */
    @Test
    void lesson2_sweeper_and_shipment_cannot_both_win() throws Exception {
        final int N = 40;
        Orders.receiveStock(TENANT, LOC, VARIANT, N, T0);
        List<UUID> orders = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            long cust = 100 + i;
            Orders.fundWallet(TENANT, cust, new BigDecimal("100.00"), T0);
            UUID id = UUID.randomUUID();
            assertInstanceOf(Orders.Ok.class, Orders.submit(id, TENANT, cust, VARIANT, LOC, 1, T0));
            orders.add(id);
        }

        // Half the reservations are swept BEFORE anyone tries to ship them, so
        // the sweeper's win is guaranteed to be exercised and the late shipment
        // must be a no-op. Leaving it to chance is how a race test quietly only
        // ever proves one direction.
        for (int i = 1; i < orders.size(); i += 2) {
            Orders.abort(orders.get(i), LATER);          // the sweeper got there first
        }

        // now every reservation gets a shipment and a sweep at the same instant
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> fs = new ArrayList<>();
            for (UUID id : orders) {
                fs.add(pool.submit(() -> { go.await(); Orders.fulfil(id, LATER); return null; }));
                fs.add(pool.submit(() -> { go.await(); ReservationSweeper.sweepOnce(LATER, 100); return null; }));
            }
            go.countDown();
            for (Future<?> f : fs) f.get(120, TimeUnit.SECONDS);
        }

        try (Connection c = Db.open()) {
            int onHand = Ledger.balance(c, Orders.onHand(LOC, VARIANT)).intValueExact();
            int reserved = Ledger.balance(c, Orders.reserved(LOC, VARIANT)).intValueExact();
            int sold = Ledger.balance(c, Orders.sold(LOC, VARIANT)).intValueExact();

            // every order settled exactly one way
            for (UUID id : orders) {
                String st = orderState(c, id);
                assertTrue("fulfilled".equals(st) || "aborted".equals(st), "order " + id + " is " + st);
            }
            assertEquals(N, onHand + reserved + sold, "units are conserved: nothing created, nothing lost");
            assertEquals(0, reserved, "nothing is still held after every reservation settled");
            assertEquals(N, countState(c, "fulfilled") + countState(c, "aborted"));
            // both directions genuinely happened, so both loser paths were tested
            assertTrue(countState(c, "aborted") >= N / 2, "the sweeper won its half, and the late shipments were no-ops");
            assertTrue(countState(c, "fulfilled") > 0, "shipments won the other half");

            assertTrue(Ledger.sumZeroViolations(c).isEmpty());
            assertTrue(Ledger.driftedAccounts(c).isEmpty());
            // the audit that would have caught a double settle, which the other two cannot
            assertTrue(ReservationSweeper.reservedMismatches(c).isEmpty(), "pooled reserved matches held reservations");
            System.out.println("lesson 2: " + N + " reservations raced by shipment and sweeper · "
                    + countState(c, "fulfilled") + " shipped, " + countState(c, "aborted")
                    + " released, units conserved, no double settle");
        }
    }

    /** LESSON 3 · the third audit notices what the first two cannot. */
    @Test
    void lesson3_the_reserved_audit_detects_a_phantom_release() throws Exception {
        Orders.receiveStock(TENANT, LOC, VARIANT, 5, T0);
        Orders.fundWallet(TENANT, 2L, new BigDecimal("500.00"), T0);
        UUID orderId = UUID.randomUUID();
        assertInstanceOf(Orders.Ok.class, Orders.submit(orderId, TENANT, 2L, VARIANT, LOC, 2, T0));

        try (Connection c = Db.open()) {
            assertTrue(ReservationSweeper.reservedMismatches(c).isEmpty(), "healthy to begin with");
        }

        // simulate the bug this design exists to prevent: a reservation marked
        // settled without its units moving. Sum-zero and drift stay clean.
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE reservations SET state = 'released' WHERE order_id = ?")) {
            ps.setObject(1, orderId);
            ps.executeUpdate();
        }

        try (Connection c = Db.open()) {
            assertTrue(Ledger.sumZeroViolations(c).isEmpty(), "the books still balance, which is the trap");
            assertTrue(Ledger.driftedAccounts(c).isEmpty(), "and no cache drift either");
            assertFalse(ReservationSweeper.reservedMismatches(c).isEmpty(),
                    "only the reserved audit sees it: 2 units held by nobody");
        }
        System.out.println("lesson 3: books balance and cache is clean, yet audit 3 catches the phantom release");
    }

    private static String orderState(Connection c, UUID id) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT state FROM orders WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getString(1); }
        }
    }

    private static int countState(Connection c, String state) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM orders WHERE state = ?")) {
            ps.setString(1, state);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }
}
