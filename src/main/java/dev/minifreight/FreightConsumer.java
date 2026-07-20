package dev.minifreight;

import dev.minimart.core.Json;
import dev.minimart.core.Outbox;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;

/**
 * HOW FREIGHT LEARNS AN ORDER EXISTS · from the topic, on its own books.
 *
 * A consumer group of its own, reading the same order topic the warehouse
 * reads, at its own pace. Checkout does not know freight exists, which is the
 * broker's whole argument, retold: shipping was added to this estate without
 * touching one line of the code that sells things.
 *
 * The claim and the effect share one transaction ON FREIGHT'S OWN DATABASE.
 * That sentence is why this class exists instead of reusing the shared
 * EventRuntime: the runtime opens the merchant's connection, and a claim
 * recorded in somebody else's books is a claim that can commit while the
 * effect it guards does not. Same lesson, correct ledger.
 *
 * order.fulfilled is the trigger, not order.placed, and the choice is a
 * business rule wearing a technical hat: in this estate an order is fulfilled
 * when the money is captured, and a parcel that leaves before the money
 * arrives is a gift with a tracking number.
 */
public final class FreightConsumer {

    public static final String GROUP = "freight";

    private final KafkaConsumer<String, String> consumer;

    public FreightConsumer(String bootstrap, String topic) {
        Properties p = new Properties();
        p.put("bootstrap.servers", bootstrap);
        p.put("group.id", GROUP);
        p.put("enable.auto.commit", "false");   // we decide when an event is done
        p.put("auto.offset.reset", "earliest"); // a new shipper reads the backlog, not just the future
        p.put("max.poll.records", "200");
        this.consumer = new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer());
        this.consumer.subscribe(List.of(topic));
    }

    /**
     * Apply one delivery. Idempotent twice over, because the two gates guard
     * different doors: the handled_events claim stops the same DELIVERY
     * applying twice, and the unique order_id inside createFromOrder stops the
     * same ORDER shipping twice when a replayed backlog arrives under event
     * keys this consumer has never seen.
     */
    public static boolean apply(String eventKey, String payload) throws Exception {
        if (!"order.fulfilled".equals(Json.str(payload, "type"))) return false;   // not ours, not an error

        String at = Json.str(payload, "at");
        Instant businessAt = at == null ? Instant.now() : Instant.parse(at);

        try (Connection c = FreightDb.open()) {
            c.setAutoCommit(false);
            try {
                if (!Outbox.recordHandled(c, eventKey, GROUP, businessAt)) {
                    c.rollback();
                    return false;
                }
                Shipments.createFromOrder(c, payload, businessAt);
                c.commit();
                return true;
            } catch (Exception e) {
                c.rollback();     // the claim goes back with the effect it guarded
                throw e;
            }
        }
    }

    /** One deterministic pass; offsets commit only after the work, so a crash
     *  replays and a replay is a no-op behind the claims. */
    public int drainOnce(Duration timeout) throws Exception {
        ConsumerRecords<String, String> records = consumer.poll(timeout);
        int applied = 0;
        for (ConsumerRecord<String, String> r : records) {
            var header = r.headers().lastHeader("event_key");
            if (header == null) continue;             // not ours to interpret
            String eventKey = new String(header.value(), StandardCharsets.UTF_8);
            if (apply(eventKey, r.value())) applied++;
        }
        if (!records.isEmpty()) consumer.commitSync();
        return applied;
    }

    public void runLoop() {
        Thread.ofVirtual().name("freight-consumer").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    drainOnce(Duration.ofMillis(500));
                } catch (Exception e) {
                    // offsets were not committed, so the batch comes back
                    try { Thread.sleep(1000); } catch (InterruptedException i) { Thread.currentThread().interrupt(); }
                }
            }
        });
    }

    public void close() { consumer.close(); }
}
