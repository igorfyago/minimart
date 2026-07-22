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
        int freightPort = Integer.parseInt(System.getenv().getOrDefault("MINIFREIGHT_PORT", "8084"));
        int carrierPort = Integer.parseInt(System.getenv().getOrDefault("CARRIERS_PORT", "8085"));

        Migrate.bootstrap();
        PayDb.bootstrap();
        AnalyticsDb.bootstrap();
        seedDemoCatalog();

        PayApi.start(payPort);
        // THE ESTATE LINK. A signed-in user arrives from the bank with a
        // token; from here on the shop sells to the customer the bank says
        // that person already is. The adapter is fail-shut: any wobble
        // (auth down, bank down, a person with no bank account) resolves to
        // the anonymous shop that has always worked.
        try {
            dev.minimart.http.MartApi.identity(dev.minimart.http.EstateIdentity.create());
            System.out.println("minimart     identity: estate SSO linked (audience mart.b4rruf3t.com)");
        } catch (Throwable t) {
            System.out.println("minimart     identity: SSO unavailable, serving anonymously · " + t);
        }
        MartApi.start(martPort);
        AnalyticsApi.start(analyticsPort);

        // Freight is OPTIONAL at boot, the same argument as the broker being
        // optional below: a shop that cannot take an order because a logistics
        // sidecar could not reach its database would be a worse system, not a
        // stricter one. A deployment that has not configured MINIFREIGHT_* yet
        // boots the till first and says plainly what it left out.
        boolean freightUp = false;
        try {
            dev.minifreight.FreightDb.bootstrap();
            dev.minifreight.FreightApi.start(freightPort);
            // The carriers are the outside world, booted here the way the seeded
            // customers are: simulated parties with no privileged path. Freight
            // reaches them by HTTP and they answer with signed webhooks, and
            // nothing in this JVM shortcuts either leg.
            dev.minifreight.CarrierSim.start(carrierPort, "http://localhost:" + freightPort);
            freightUp = true;
        } catch (Exception e) {
            System.out.println("minifreight: not booting (" + e.getMessage()
                    + ") · the shop sells on; orders will ship once freight's database is reachable");
        }
        startEventPipeline(carrierPort, freightUp);

        System.out.println("minipay      (processor) up: http://localhost:" + payPort + "/v1/payment_intents");
        System.out.println("minimart     (merchant)  up: http://localhost:" + martPort + "/api/catalog");
        System.out.println("minianalytics(reporting) up: http://localhost:" + analyticsPort + "/api/analytics/mrr");
        if (freightUp) {
            System.out.println("minifreight  (logistics) up: http://localhost:" + freightPort + "/api/freight/audit");
        }
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
    private static void startEventPipeline(int carrierPort, boolean freightUp) {
        String kafka = System.getenv().getOrDefault("MINIMART_KAFKA", "");
        if (kafka.isBlank()) {
            System.out.println("kafka: not configured, events will queue in the outbox");
            return;
        }
        Thread.ofVirtual().name("pipeline-boot").start(() -> {
            try {
                new dev.minimart.core.OutboxRelay(kafka).runLoop(1000);
                new AnalyticsConsumer(kafka, dev.minimart.commerce.Billing.TOPIC).runLoop();
                // The warehouse reacts to orders without checkout knowing it
                // exists. Adding this required no change to the code that sells
                // things, which is the entire argument for the broker.
                new dev.minimart.core.EventConsumer(kafka,
                        dev.minimart.commerce.Orders.TOPIC_ORDERS,
                        dev.minimart.commerce.Replenishment.CONSUMER,
                        dev.minimart.commerce.Replenishment::onOrderPlaced).runLoop(2000);
                if (freightUp) {
                    // Freight is the second proof of the same argument: shipping
                    // arrived in this estate without one line of checkout changing.
                    // Its consumer claims on its own books, its relay ships its own
                    // outbox, and its driver walks the carrier saga.
                    new dev.minifreight.FreightConsumer(kafka,
                            dev.minimart.commerce.Orders.TOPIC_ORDERS).runLoop();
                    new dev.minifreight.FreightRelay(kafka).runLoop(1000);
                    new dev.minifreight.FreightDriver("http://localhost:" + carrierPort).runLoop(2000);
                }
                // The saga's return leg: when freight fails a shipment, the
                // MERCHANT compensates on its own books · goods restocked,
                // wallet money returned, card money recorded as owed. This
                // consumer runs on the merchant's database, so it needs no
                // freight boot to be correct: with freight down it simply
                // reads an empty topic.
                new dev.minimart.core.EventConsumer(kafka,
                        dev.minimart.commerce.Undeliverable.TOPIC_SHIPMENTS,
                        dev.minimart.commerce.Undeliverable.CONSUMER,
                        dev.minimart.commerce.Undeliverable::onShipmentFailed).runLoop(2000);
                System.out.println("kafka: relay, analytics, replenishment"
                        + (freightUp ? ", freight" : "") + " and undeliverable consumers running against " + kafka);
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
