package dev.minimart.core;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;

/**
 * THE TRANSPORT, AND NOTHING ELSE.
 *
 * Everything interesting about failure handling lives in EventRuntime, which
 * has no broker in it and can be tested directly. What is left here is the
 * genuinely Kafka-shaped part: joining a group, polling, finding the producer's
 * event key in a header, and deciding when to commit an offset.
 *
 * Two decisions carry the weight:
 *
 * enable.auto.commit=false, and offsets committed only after the batch has been
 * put through the runtime. Auto-commit advances the offset on a timer, so a
 * crash mid-batch skips events that were never processed, and a skipped event
 * is a hole nothing downstream can detect.
 *
 * The commit is UNCONDITIONAL once the batch is through. A failed event has
 * already been copied into the local retry queue by then, so advancing past it
 * loses nothing and unblocks everything behind it. That is the whole answer to
 * the poison message: the offset must never be held hostage by one record.
 */
public final class EventConsumer {

    private final KafkaConsumer<String, String> consumer;
    private final EventRuntime runtime;

    public EventConsumer(String bootstrap, String topic, String group, EventRuntime.Handler handler) {
        this(bootstrap, topic, new EventRuntime(topic, group, handler));
    }

    public EventConsumer(String bootstrap, String topic, EventRuntime runtime) {
        this.runtime = runtime;
        Properties p = new Properties();
        p.put("bootstrap.servers", bootstrap);
        p.put("group.id", runtime.group());
        p.put("enable.auto.commit", "false");   // the runtime decides when an event is done
        p.put("auto.offset.reset", "earliest"); // a new consumer reads history, not just the future
        p.put("max.poll.records", "200");
        this.consumer = new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer());
        this.consumer.subscribe(List.of(topic));
    }

    public record Pass(int handled, int skipped, int failed, int buried) {}

    /** One deterministic pass, so a simulation can drain at a tick boundary
     *  instead of racing a background thread. */
    public Pass drainOnce(Duration timeout, Instant businessAt) throws SQLException {
        ConsumerRecords<String, String> records = consumer.poll(timeout);
        int handled = 0, skipped = 0, failed = 0, buried = 0;

        for (ConsumerRecord<String, String> r : records) {
            // The PRODUCER names the event. A consumer that invents its own key
            // from the offset or a hash of the body guesses differently the
            // moment the topic is repartitioned or the payload gains a field.
            var header = r.headers().lastHeader("event_key");
            String eventKey = header != null
                    ? new String(header.value(), StandardCharsets.UTF_8)
                    : r.key();
            if (eventKey == null) { skipped++; continue; }

            switch (runtime.apply(eventKey, r.value(), businessAt)) {
                case HANDLED -> handled++;
                case DUPLICATE -> skipped++;
                case RETRY -> failed++;
                case BURIED -> { failed++; buried++; }
            }
        }
        if (!records.isEmpty()) consumer.commitSync();
        return new Pass(handled, skipped, failed, buried);
    }

    public int retryPending(Instant businessAt) throws SQLException { return runtime.retryPending(businessAt); }

    public void runLoop(long everyMillis) {
        Thread.ofVirtual().name("consumer-" + runtime.group()).start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    drainOnce(Duration.ofMillis(500), Instant.now());
                    runtime.retryPending(Instant.now());
                    Thread.sleep(everyMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    try { Thread.sleep(everyMillis); } catch (InterruptedException i) { Thread.currentThread().interrupt(); }
                }
            }
        });
    }

    public void close() { consumer.close(); }
}
