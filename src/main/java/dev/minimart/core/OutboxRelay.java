package dev.minimart.core;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.sql.Connection;
import java.time.Instant;
import java.util.Properties;

/**
 * THE RELAY · it ships what the outbox has already promised.
 *
 * acks=all plus an idempotent producer, and the outbox row is marked published
 * ONLY after the broker acknowledges. That ordering is the crash-safety
 * argument in one sentence: crash between send and mark, and the event is sent
 * again. At-least-once, never at-most-once, because a duplicate can be deduped
 * downstream and a loss can never be detected at all.
 *
 * publishPending() is one deterministic pass, so a simulation can call it at a
 * tick boundary. runLoop() is the production form.
 */
public final class OutboxRelay {

    private final KafkaProducer<String, String> producer;

    public OutboxRelay(String bootstrap) {
        Properties p = new Properties();
        p.put("bootstrap.servers", bootstrap);
        p.put("acks", "all");                     // every in-sync replica, or it did not happen
        p.put("enable.idempotence", "true");      // no duplicates from producer-side retries
        p.put("retries", "5");
        p.put("delivery.timeout.ms", "15000");
        p.put("request.timeout.ms", "5000");
        p.put("max.block.ms", "5000");
        this.producer = new KafkaProducer<>(p, new StringSerializer(), new StringSerializer());
    }

    /** One pass. Returns how many events genuinely reached the broker. */
    public int publishPending(int limit) throws Exception {
        int sent = 0;
        try (Connection c = Db.open()) {
            for (Outbox.Row row : Outbox.pending(c, limit)) {
                // .get() blocks for the acknowledgement on purpose: we must not
                // mark anything published on the strength of a hope
                ProducerRecord<String, String> rec =
                        new ProducerRecord<>(row.topic(), row.key(), row.payload());
                // the consumer must never have to invent the dedup key
                rec.headers().add("event_key", row.eventKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                producer.send(rec).get();
                Outbox.markPublished(c, row.id(), Instant.now());
                sent++;
            }
        }
        return sent;
    }

    /** Production: a virtual thread, polling politely. */
    public void runLoop(long everyMillis) {
        Thread.ofVirtual().name("outbox-relay").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    publishPending(200);
                    Thread.sleep(everyMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    // the outbox still holds it; the next pass tries again
                    try { Thread.sleep(everyMillis); } catch (InterruptedException i) { Thread.currentThread().interrupt(); }
                }
            }
        });
    }

    public void close() { producer.close(); }
}
