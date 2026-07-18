package dev.minimart;

import dev.minimart.core.Db;
import dev.minimart.core.Migrate;
import dev.minimart.http.MartApi;
import dev.minianalytics.AnalyticsApi;
import dev.minianalytics.AnalyticsConsumer;
import dev.minianalytics.AnalyticsDb;
import dev.minipay.PayApi;
import dev.minipay.PayDb;

import java.sql.Connection;
import java.sql.Statement;

/**
 * Three services, one command.
 *
 * They are genuinely separate: separate databases, separate ledgers, and they
 * speak only HTTP and Kafka to each other, never SQL. Booting them in one JVM
 * is a development convenience and nothing more, exactly as production would
 * run them as three containers. The test of that claim is simple: analytics
 * cannot answer a single question by querying minimart, because there is no
 * connection from one to the other and no schema they share.
 *
 *   minimart      (the merchant)  :8081
 *   minipay       (the processor) :8082
 *   minianalytics (the reporting) :8083
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        int martPort = Integer.parseInt(System.getenv().getOrDefault("MINIMART_PORT", "8081"));
        int payPort = Integer.parseInt(System.getenv().getOrDefault("MINIPAY_PORT", "8082"));

        int analyticsPort = Integer.parseInt(System.getenv().getOrDefault("ANALYTICS_PORT", "8083"));

        Migrate.bootstrap();
        PayDb.bootstrap();
        AnalyticsDb.bootstrap();
        seedDemoCatalog();

        PayApi.start(payPort);
        MartApi.start(martPort);
        AnalyticsApi.start(analyticsPort);
        startEventPipeline();

        System.out.println("minipay      (processor) up: http://localhost:" + payPort + "/v1/payment_intents");
        System.out.println("minimart     (merchant)  up: http://localhost:" + martPort + "/api/catalog");
        System.out.println("minianalytics(reporting) up: http://localhost:" + analyticsPort + "/api/analytics/mrr");
        Thread.currentThread().join();
    }

    /**
     * The relay and the consumer, both optional.
     *
     * If the broker is unreachable the storefront still sells and the processor
     * still charges: events accumulate in the outbox, which is precisely what
     * the outbox is for. Nothing is lost, analytics simply falls behind, and it
     * catches up on its own when Kafka returns. A shop that cannot take an order
     * because a reporting pipeline is down would be a worse system, not a
     * stricter one.
     */
    private static void startEventPipeline() {
        String kafka = System.getenv().getOrDefault("MINIMART_KAFKA", "");
        if (kafka.isBlank()) {
            System.out.println("kafka: not configured, events will queue in the outbox");
            return;
        }
        Thread.ofVirtual().name("pipeline-boot").start(() -> {
            try {
                new dev.minimart.core.OutboxRelay(kafka).runLoop(1000);
                new AnalyticsConsumer(kafka, dev.minimart.commerce.Billing.TOPIC).runLoop();
                System.out.println("kafka: relay and analytics consumer running against " + kafka);
            } catch (Exception e) {
                System.out.println("kafka: unavailable (" + e.getMessage() + "), events queue in the outbox");
            }
        });
    }

    /** A fictional storefront to have something to sell. Products are data. */
    private static void seedDemoCatalog() throws Exception {
        try (Connection c = Db.open(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO tenants(slug) VALUES ('helix') ON CONFLICT DO NOTHING");
            st.execute("""
                INSERT INTO variants(id, tenant, title, price) VALUES
                  ('v-recovery-30',  'helix', 'Recovery Stack · 30 day',   79.00),
                  ('v-focus-30',     'helix', 'Focus Stack · 30 day',      89.00),
                  ('v-sleep-30',     'helix', 'Sleep Stack · 30 day',      69.00),
                  ('v-starter-14',   'helix', 'Starter Trial · 14 day',    29.00)
                ON CONFLICT (id) DO NOTHING""");
        }
    }
}
