package dev.minifreight;

import dev.minimart.core.Outbox;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.sql.Connection;
import java.time.Instant;
import java.util.Properties;

/**
 * The relay for FREIGHT'S outbox. The same thirty lines as the merchant's
 * relay, over a different database, and the duplication is the doctrine: the
 * shared OutboxRelay opens the merchant's connection, and parameterising it
 * with "whose books" would hand every service a constructor argument capable
 * of publishing another service's promises. Each service ships its own.
 */
public final class FreightRelay {

    private final KafkaProducer<String, String> producer;

    public FreightRelay(String bootstrap) {
        Properties p = new Properties();
        p.put("bootstrap.servers", bootstrap);
        p.put("acks", "all");
        p.put("enable.idempotence", "true");
        p.put("retries", "5");
        p.put("delivery.timeout.ms", "15000");
        p.put("request.timeout.ms", "5000");
        p.put("max.block.ms", "5000");
        this.producer = new KafkaProducer<>(p, new StringSerializer(), new StringSerializer());
    }

    /** One pass. Marked published only after the broker acknowledges, so a
     *  crash resends and consumers dedupe, never the other way round. */
    public int publishPending(int limit) throws Exception {
        int sent = 0;
        try (Connection c = FreightDb.open()) {
            for (Outbox.Row row : Outbox.pending(c, limit)) {
                ProducerRecord<String, String> rec =
                        new ProducerRecord<>(row.topic(), row.key(), row.payload());
                rec.headers().add("event_key", row.eventKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                producer.send(rec).get();
                Outbox.markPublished(c, row.id(), Instant.now());
                sent++;
            }
        }
        return sent;
    }

    public void runLoop(long everyMillis) {
        Thread.ofVirtual().name("freight-relay").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    publishPending(200);
                    Thread.sleep(everyMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    try { Thread.sleep(everyMillis); } catch (InterruptedException i) { Thread.currentThread().interrupt(); }
                }
            }
        });
    }

    public void close() { producer.close(); }
}
