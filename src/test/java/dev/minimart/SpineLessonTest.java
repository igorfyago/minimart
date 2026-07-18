package dev.minimart;

import dev.minimart.commerce.Orders;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SLICE 1 · THE SPINE.
 *
 * Four lessons. If these hold, three theses hold with them: a reservation is
 * a hold, units are just another currency, and compensation restores the world
 * exactly. Everything after this (carts, multi-line, the Kafka saga, the
 * sweeper, returns) is additive.
 */
class SpineLessonTest {

    static final String TENANT = "helix";
    static final String LOC = "MAD";
    static final String VARIANT = "v-mots-10mg";
    static final Instant T0 = Instant.parse("2026-03-01T09:00:00Z");

    @BeforeAll
    static void migrate() { Migrate.run(); }

    @BeforeEach
    void reset() throws Exception {
        try (Connection c = Db.open(); var st = c.createStatement()) {
            // TRUNCATE, not DELETE: the append-only trigger refuses row deletes,
            // which is the guard doing its job even on its own author
            st.execute("TRUNCATE reservations, orders, entries, transactions, accounts, variants, tenants RESTART IDENTITY CASCADE");
            st.execute("INSERT INTO tenants(slug) VALUES ('" + TENANT + "')");
            st.execute("INSERT INTO variants(id, tenant, title, price) VALUES ('" + VARIANT + "','" + TENANT + "','MOTS-c 10mg', 40.00)");
        }
    }

    /** LESSON 1 · fifty buyers, ten units. Overselling is not defended against, it is impossible. */
    @Test
    void lesson1_concurrent_buyers_cannot_oversell() throws Exception {
        Orders.receiveStock(TENANT, LOC, VARIANT, 10, T0);
        for (long cust = 1; cust <= 50; cust++) Orders.fundWallet(TENANT, cust, new BigDecimal("1000.00"), T0);

        AtomicInteger ok = new AtomicInteger(), out = new AtomicInteger();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> fs = new ArrayList<>();
            for (long cust = 1; cust <= 50; cust++) {
                final long id = cust;
                fs.add(pool.submit(() -> {
                    go.await();
                    Orders.Result r = Orders.submit(UUID.randomUUID(), TENANT, id, VARIANT, LOC, 1, T0);
                    if (r instanceof Orders.Ok) ok.incrementAndGet();
                    else if (r instanceof Orders.OutOfStock) out.incrementAndGet();
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        }

        assertEquals(10, ok.get(), "exactly ten buyers can win");
        assertEquals(40, out.get(), "the other forty are told there is no stock");
        try (Connection c = Db.open()) {
            assertEquals(0, Ledger.balance(c, Orders.onHand(LOC, VARIANT)).intValueExact(), "shelf is empty");
            assertEquals(10, Ledger.balance(c, Orders.reserved(LOC, VARIANT)).intValueExact(), "ten units held");
            assertTrue(Ledger.sumZeroViolations(c).isEmpty());
            assertTrue(Ledger.driftedAccounts(c).isEmpty());
        }
        System.out.println("lesson 1: 50 concurrent buyers, 10 units · " + ok.get() + " sold, " + out.get() + " refused, never negative");
    }

    /** LESSON 2 · the same order id twenty times over. Agents retry; money must not. */
    @Test
    void lesson2_the_same_order_id_moves_money_once() throws Exception {
        Orders.receiveStock(TENANT, LOC, VARIANT, 100, T0);
        Orders.fundWallet(TENANT, 7L, new BigDecimal("1000.00"), T0);
        UUID orderId = UUID.randomUUID();

        AtomicInteger ok = new AtomicInteger(), dup = new AtomicInteger();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                fs.add(pool.submit(() -> {
                    go.await();
                    Orders.Result r = Orders.submit(orderId, TENANT, 7L, VARIANT, LOC, 2, T0);
                    if (r instanceof Orders.Ok) ok.incrementAndGet();
                    else if (r instanceof Orders.AlreadyProcessed) dup.incrementAndGet();
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        }

        assertEquals(1, ok.get(), "one submission wins");
        assertEquals(19, dup.get(), "nineteen are told it already happened");
        try (Connection c = Db.open()) {
            assertEquals(1, count(c, "SELECT COUNT(*) FROM orders WHERE id = '" + orderId + "'"));
            assertEquals(1, count(c, "SELECT COUNT(*) FROM reservations WHERE order_id = '" + orderId + "'"));
            assertEquals(4, count(c, "SELECT COUNT(*) FROM entries WHERE tx_id = '" + orderId + "'"));
            assertEquals(new BigDecimal("920.00"), Ledger.balance(c, Orders.wallet(7L)).stripTrailingZeros().setScale(2));
            assertEquals(98, Ledger.balance(c, Orders.onHand(LOC, VARIANT)).intValueExact());
        }
        System.out.println("lesson 2: 20 concurrent submits of one order id · 1 order, 1 reservation, 4 entries");
    }

    /** LESSON 3 · the postings. Money and goods settle in the same commit, each summing to zero. */
    @Test
    void lesson3_fulfilment_posts_both_ledgers() throws Exception {
        Orders.receiveStock(TENANT, LOC, VARIANT, 5, T0);
        Orders.fundWallet(TENANT, 3L, new BigDecimal("200.00"), T0);
        UUID orderId = UUID.randomUUID();

        assertInstanceOf(Orders.Ok.class, Orders.submit(orderId, TENANT, 3L, VARIANT, LOC, 2, T0));
        Orders.fulfil(orderId, T0.plusSeconds(3600));

        try (Connection c = Db.open()) {
            assertEquals(new BigDecimal("120.00"), Ledger.balance(c, Orders.wallet(3L)).stripTrailingZeros().setScale(2), "paid 80");
            assertEquals(BigDecimal.ZERO.setScale(2), Ledger.balance(c, Orders.holds(TENANT)).stripTrailingZeros().setScale(2), "holds released");
            assertEquals(new BigDecimal("80.00"), Ledger.balance(c, Orders.revenue(TENANT)).stripTrailingZeros().setScale(2), "revenue earned");
            assertEquals(3, Ledger.balance(c, Orders.onHand(LOC, VARIANT)).intValueExact());
            assertEquals(0, Ledger.balance(c, Orders.reserved(LOC, VARIANT)).intValueExact());
            assertEquals(2, Ledger.balance(c, Orders.sold(LOC, VARIANT)).intValueExact());
            assertTrue(Ledger.sumZeroViolations(c).isEmpty(), "both currencies sum to zero");
            assertTrue(Ledger.driftedAccounts(c).isEmpty());
        }
        System.out.println("lesson 3: fulfil posts SIMEUR and UNIT legs in one tx · both sum to zero");
    }

    /** LESSON 4 · compensation. An aborted order leaves the world exactly as it found it. */
    @Test
    void lesson4_abort_restores_the_world() throws Exception {
        Orders.receiveStock(TENANT, LOC, VARIANT, 5, T0);
        Orders.fundWallet(TENANT, 4L, new BigDecimal("200.00"), T0);
        UUID orderId = UUID.randomUUID();

        assertInstanceOf(Orders.Ok.class, Orders.submit(orderId, TENANT, 4L, VARIANT, LOC, 3, T0));
        Orders.abort(orderId, T0.plusSeconds(60));
        // and again: compensation is idempotent, because agents retry that too
        Orders.abort(orderId, T0.plusSeconds(120));

        try (Connection c = Db.open()) {
            assertEquals(new BigDecimal("200.00"), Ledger.balance(c, Orders.wallet(4L)).stripTrailingZeros().setScale(2), "made whole");
            assertEquals(5, Ledger.balance(c, Orders.onHand(LOC, VARIANT)).intValueExact(), "back on the shelf");
            assertEquals(0, Ledger.balance(c, Orders.reserved(LOC, VARIANT)).intValueExact());
            assertEquals(BigDecimal.ZERO.setScale(2), Ledger.balance(c, Orders.holds(TENANT)).stripTrailingZeros().setScale(2));
            assertTrue(Ledger.sumZeroViolations(c).isEmpty());
            assertTrue(Ledger.driftedAccounts(c).isEmpty());
        }
        System.out.println("lesson 4: abort (twice) restores wallet and shelf exactly · books balance");
    }

    private static long count(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next(); return rs.getLong(1);
        }
    }
}
