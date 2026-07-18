package dev.minianalytics;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

/**
 * THE CONSUMER SIDE OF THE CONTRACT.
 *
 * A consumer group of its own, so analytics reads the same topic minimart's
 * other subscribers read, at its own pace, with its own offsets. Falling behind
 * or being switched off for a day costs nothing but lag, and none of it is
 * visible to the service producing the events. That independence is the reason
 * for the broker in the first place.
 *
 * Two decisions here are the whole of at-least-once:
 *
 * enable.auto.commit=false, and offsets committed only AFTER the batch has been
 * applied. Auto-commit would advance the offset on a timer, so a crash mid-batch
 * would skip events that were never processed, and a skipped event is a hole
 * nothing downstream can detect. Committing after means a crash REPLAYS, and a
 * replay is harmless because every event lands behind an idempotency claim.
 *
 * The dedup key arrives in a HEADER, written by the producer. A consumer that
 * invents its own key, from the offset or from a hash of the body, is guessing,
 * and it guesses differently after the topic is repartitioned or the payload
 * gains a field.
 */
public final class AnalyticsConsumer {

    public static final String GROUP = "analytics";

    private final KafkaConsumer<String, String> consumer;

    public AnalyticsConsumer(String bootstrap, String topic) {
        Properties p = new Properties();
        p.put("bootstrap.servers", bootstrap);
        p.put("group.id", GROUP);
        p.put("enable.auto.commit", "false");   // we decide when an event is done
        p.put("auto.offset.reset", "earliest"); // a new consumer reads history, not just the future
        p.put("max.poll.records", "200");
        this.consumer = new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer());
        this.consumer.subscribe(List.of(topic));
    }

    /** One deterministic pass, so a simulation can drain the topic at a tick
     *  boundary rather than racing a background thread. Returns how many events
     *  actually changed anything: duplicates are counted as delivered, not applied. */
    public int drainOnce(Duration timeout) throws Exception {
        ConsumerRecords<String, String> records = consumer.poll(timeout);
        int applied = 0;
        for (ConsumerRecord<String, String> r : records) {
            var header = r.headers().lastHeader("event_key");
            if (header == null) continue;       // not ours to interpret
            String eventKey = new String(header.value(), StandardCharsets.UTF_8);
            if (Projection.apply(eventKey, r.value())) applied++;
        }
        // AFTER the work, never before
        if (!records.isEmpty()) consumer.commitSync();
        return applied;
    }

    public void runLoop() {
        Thread.ofVirtual().name("analytics-consumer").start(() -> {
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
