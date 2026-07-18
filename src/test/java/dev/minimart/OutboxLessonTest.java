package dev.minimart;

import dev.minimart.commerce.Orders;
import dev.minimart.core.Db;
import dev.minimart.core.Migrate;
import dev.minimart.core.Outbox;
import dev.minimart.core.OutboxRelay;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE TRANSACTIONAL OUTBOX · the one pattern that makes "write to my database
 * AND tell the world" safe.
 *
 * You cannot commit to Postgres and to Kafka atomically. Crash between the two
 * and you either have an order nobody was told about, or worse, an announcement
 * for an order that never happened. So the event is never written to two
 * systems: it is INSERTed into an outbox table inside the SAME transaction as
 * the money and the goods, and shipped afterwards by a relay that marks it
 * published only once the broker has acknowledged it.
 *
 * Loss becomes impossible. Duplicates become possible, and are designed for.
 */
class OutboxLessonTest {

    static final String TENANT = "helix", LOC = "MAD", VARIANT = "v-recovery-30";
    static final Instant T0 = Instant.parse("2026-07-01T09:00:00Z");
    static final String KAFKA = System.getenv().getOrDefault("MINIMART_KAFKA", "localhost:9093");

    @BeforeAll
    static void migrate() throws Exception { Migrate.bootstrap(); }

    @BeforeEach
    void reset() throws Exception {
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE outbox, dunning_attempts, invoices, subscriptions, reservations, orders, entries, transactions, accounts, variants, tenants RESTART IDENTITY CASCADE");
            st.execute("INSERT INTO tenants(slug) VALUES ('" + TENANT + "')");
            st.execute("INSERT INTO variants(id, tenant, title, price) VALUES ('" + VARIANT + "','" + TENANT + "','Recovery Stack', 79.00)");
        }
        Orders.receiveStock(TENANT, LOC, VARIANT, 5, T0);
    }

    /** LESSON 1 · THE DUAL WRITE, SOLVED. The event and the goods share one fate. */
    @Test
    void lesson1_the_event_commits_with_the_order_or_not_at_all() throws Exception {
        UUID good = UUID.randomUUID();
        assertInstanceOf(Orders.Ok.class,
                Orders.submit(good, TENANT, 1L, VARIANT, LOC, 2, T0, false));
        assertEquals(1, outboxCount(), "a committed order leaves exactly one event behind");

        // now an order that CANNOT succeed: more units than exist
        UUID bad = UUID.randomUUID();
        assertInstanceOf(Orders.OutOfStock.class,
                Orders.submit(bad, TENANT, 2L, VARIANT, LOC, 99, T0, false));
        assertEquals(1, outboxCount(),
                "the failed order rolled back, and took its announcement with it");

        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("SELECT topic, key, payload FROM outbox");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertEquals("minimart.orders", rs.getString(1));
            assertEquals(good.toString(), rs.getString(2),
                    "partitioned by ORDER id, so a cancel can never overtake its own placement");
            assertTrue(rs.getString(3).contains("order.placed"), rs.getString(3));
        }
        System.out.println("lesson 1: committed order -> 1 event; rolled-back order -> 0. No dual write exists.");
    }

    /** LESSON 2 · MARK AFTER ACK. Unpublished until the broker says otherwise. */
    @Test
    void lesson2_the_relay_marks_published_only_after_the_broker_acks() throws Exception {
        UUID id = UUID.randomUUID();
        Orders.submit(id, TENANT, 3L, VARIANT, LOC, 1, T0, false);
        assertEquals(1, pendingCount(), "born unpublished");

        OutboxRelay relay = new OutboxRelay(KAFKA);
        int sent = relay.publishPending(50);

        assertEquals(1, sent);
        assertEquals(0, pendingCount(), "marked only once the broker acknowledged it");
        // and it really is on the topic, not merely marked
        assertTrue(readTopic("minimart.orders").stream().anyMatch(v -> v.contains(id.toString())),
                "the event is genuinely on the broker");

        // a second pass has nothing to do: publishing is not repeated
        assertEquals(0, relay.publishPending(50), "already published, nothing to resend");
        System.out.println("lesson 2: pending -> published only after acks=all, and the bytes are on the topic");
    }

    /** LESSON 3 · AT LEAST ONCE, HANDLED ONCE. Duplicates are designed for. */
    @Test
    void lesson3_a_duplicated_delivery_is_applied_once() throws Exception {
        UUID id = UUID.randomUUID();
        Orders.submit(id, TENANT, 4L, VARIANT, LOC, 1, T0, false);

        // the same event handed to the consumer five times, as a rebalance would
        String eventKey = "order.placed:" + id;
        for (int i = 0; i < 5; i++) {
            Outbox.recordHandled(eventKey, "test-consumer", T0);
        }
        assertEquals(1, handledCount(eventKey), "five deliveries, one effect");
        System.out.println("lesson 3: the same event delivered 5 times is applied once, by primary key");
    }

    // ------------------------------------------------------------------ helpers

    private static long outboxCount() throws Exception { return one("SELECT COUNT(*) FROM outbox"); }
    private static long pendingCount() throws Exception {
        return one("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL");
    }
    private static long handledCount(String key) throws Exception {
        return one("SELECT COUNT(*) FROM handled_events WHERE event_key = '" + key + "'");
    }

    private static long one(String sql) throws Exception {
        try (Connection c = Db.open(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
    }

    private static List<String> readTopic(String topic) {
        Properties p = new Properties();
        p.put("bootstrap.servers", KAFKA);
        p.put("group.id", "lesson-reader-" + UUID.randomUUID());
        p.put("auto.offset.reset", "earliest");
        try (KafkaConsumer<String, String> c =
                     new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer())) {
            var parts = c.partitionsFor(topic);
            if (parts == null || parts.isEmpty()) return List.of();
            var tps = parts.stream().map(i -> new TopicPartition(i.topic(), i.partition())).toList();
            c.assign(tps);
            c.seekToBeginning(tps);
            List<String> out = new java.util.ArrayList<>();
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> recs = c.poll(Duration.ofMillis(400));
                recs.forEach(r -> out.add(r.value()));
                if (!out.isEmpty()) break;
            }
            return out;
        }
    }
}
