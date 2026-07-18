package dev.minimart;

import com.sun.net.httpserver.HttpServer;
import dev.minimart.commerce.Checkout;
import dev.minimart.commerce.Orders;
import dev.minimart.commerce.ReservationSweeper;
import dev.minimart.core.Db;
import dev.minimart.core.Ledger;
import dev.minimart.core.Migrate;
import dev.minimart.http.MartApi;
import dev.minimart.sim.SimRunner;
import dev.minipay.PayApi;
import dev.minipay.PayDb;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE SYNTHETIC POPULATION.
 *
 * A seeded crowd of agent customers shops through the real HTTP API while a
 * compressed clock runs a week past in seconds. These lessons prove the three
 * mechanisms everything later depends on: determinism, survival under
 * concurrency, and idempotent replay.
 */
class SimLessonTest {

    static HttpServer mart, pay;
    static final int MART_PORT = 18110, PAY_PORT = 18111;
    static final String BASE = "http://localhost:" + MART_PORT;
    static final String TENANT = "helix", LOC = "MAD";
    static final Instant T0 = Instant.parse("2026-05-01T00:00:00Z");

    @BeforeAll
    static void boot() throws Exception {
        Migrate.run();
        PayDb.bootstrap();
        Checkout.payBaseUrl = "http://localhost:" + PAY_PORT;
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
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE reservations, orders, entries, transactions, accounts, variants, tenants RESTART IDENTITY CASCADE");
            st.execute("INSERT INTO tenants(slug) VALUES ('" + TENANT + "')");
            st.execute("""
                INSERT INTO variants(id, tenant, title, price) VALUES
                  ('v-recovery-30','helix','Recovery Stack', 79.00),
                  ('v-focus-30','helix','Focus Stack', 89.00),
                  ('v-sleep-30','helix','Sleep Stack', 69.00),
                  ('v-starter-14','helix','Starter Trial', 29.00)""");
        }
        try (Connection c = PayDb.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE idempotency_keys, payment_intents, entries, transactions, accounts RESTART IDENTITY CASCADE");
        }
        for (String v : List.of("v-recovery-30", "v-focus-30", "v-sleep-30", "v-starter-14"))
            Orders.receiveStock(TENANT, LOC, v, 400, T0);
    }

    /** LESSON 1 · the same seed produces the same week, twice. */
    @Test
    void lesson1_a_seeded_population_is_reproducible() throws Exception {
        SimRunner sim = new SimRunner(BASE);
        var first = sim.run("run-a", 25, 24, TENANT, LOC, T0, Duration.ofHours(1));
        String h1 = sha(first.decisions());

        reset();                                   // wipe the world, run it again
        var second = sim.run("run-a", 25, 24, TENANT, LOC, T0, Duration.ofHours(1));
        String h2 = sha(second.decisions());

        assertEquals(h1, h2, "same seed, same decisions, despite running concurrently");
        assertTrue(first.placed() > 0, "the population actually bought things");
        System.out.println("lesson 1: 25 agents x 24 ticks reproduced exactly · " + first.placed()
                + " placed, digest " + h1.substring(0, 12));
    }

    /** LESSON 2 · a different seed is a different crowd. */
    @Test
    void lesson2_a_different_seed_is_a_different_population() throws Exception {
        SimRunner sim = new SimRunner(BASE);
        var a = sim.run("run-a", 25, 24, TENANT, LOC, T0, Duration.ofHours(1));
        reset();
        var b = sim.run("run-b", 25, 24, TENANT, LOC, T0, Duration.ofHours(1));
        assertNotEquals(sha(a.decisions()), sha(b.decisions()), "the seed genuinely drives behaviour");
        System.out.println("lesson 2: seed a placed " + a.placed() + ", seed b placed " + b.placed());
    }

    /** LESSON 3 · the platform survives the crowd with its books intact. */
    @Test
    void lesson3_the_platform_survives_a_week_of_traffic() throws Exception {
        SimRunner sim = new SimRunner(BASE);
        var r = sim.run("run-week", 40, 48, TENANT, LOC, T0, Duration.ofHours(1));

        try (Connection c = Db.open()) {
            assertTrue(Ledger.sumZeroViolations(c).isEmpty(), "books balance after the whole week");
            assertTrue(Ledger.driftedAccounts(c).isEmpty(), "no cached balance drifted");
            assertTrue(ReservationSweeper.reservedMismatches(c).isEmpty(), "no phantom reservations");

            // units are conserved for every product: nothing created, nothing lost
            for (String v : List.of("v-recovery-30", "v-focus-30", "v-sleep-30", "v-starter-14")) {
                int on = Ledger.balance(c, Orders.onHand(LOC, v)).intValueExact();
                int res = Ledger.balance(c, Orders.reserved(LOC, v)).intValueExact();
                int sold = Ledger.balance(c, Orders.sold(LOC, v)).intValueExact();
                assertEquals(400, on + res + sold, "units conserved for " + v);
            }
            long orders = count(c, "SELECT COUNT(*) FROM orders");
            assertEquals(orders, count(c, "SELECT COUNT(*) FROM reservations"), "one reservation per order");
        }
        try (Connection c = PayDb.open()) {
            assertTrue(Ledger.sumZeroViolations(c).isEmpty(), "the processor's books balance too");
        }
        System.out.println("lesson 3: 40 agents x 48 ticks · " + r.placed() + " placed, " + r.shipped()
                + " shipped, " + r.released() + " expired reservations reclaimed, all audits clean");
    }

    /** LESSON 4 · replaying a tick creates nothing new, because ids are derived. */
    @Test
    void lesson4_replaying_the_run_is_idempotent() throws Exception {
        SimRunner sim = new SimRunner(BASE);
        sim.run("run-replay", 20, 12, TENANT, LOC, T0, Duration.ofHours(1));
        long ordersAfterFirst, entriesAfterFirst;
        try (Connection c = Db.open()) {
            ordersAfterFirst = count(c, "SELECT COUNT(*) FROM orders");
            entriesAfterFirst = count(c, "SELECT COUNT(*) FROM entries");
        }

        // the driver crashed and someone re-ran the identical window
        sim.run("run-replay", 20, 12, TENANT, LOC, T0, Duration.ofHours(1));

        try (Connection c = Db.open()) {
            assertEquals(ordersAfterFirst, count(c, "SELECT COUNT(*) FROM orders"),
                    "a replayed run creates no new orders: the ids are derived, not random");
            assertEquals(entriesAfterFirst, count(c, "SELECT COUNT(*) FROM entries"),
                    "and posts no new ledger entries");
            assertTrue(Ledger.sumZeroViolations(c).isEmpty());
        }
        System.out.println("lesson 4: the same run replayed · " + ordersAfterFirst + " orders, unchanged");
    }

    private static String sha(List<String> lines) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (String l : lines) md.update(l.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(md.digest());
    }

    private static long count(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next(); return rs.getLong(1);
        }
    }
}
