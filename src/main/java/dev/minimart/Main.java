package dev.minimart;

import dev.minimart.core.Db;
import dev.minimart.core.Migrate;
import dev.minimart.http.MartApi;
import dev.minipay.PayApi;
import dev.minipay.PayDb;

import java.sql.Connection;
import java.sql.Statement;

/**
 * Both services, one command.
 *
 * They are genuinely separate: separate databases, separate ledgers, and they
 * speak only HTTP to each other. Booting them together is a development
 * convenience, exactly as production would run them as two containers.
 *
 *   minimart (the merchant)  :8081
 *   minipay  (the processor) :8082
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        int martPort = Integer.parseInt(System.getenv().getOrDefault("MINIMART_PORT", "8081"));
        int payPort = Integer.parseInt(System.getenv().getOrDefault("MINIPAY_PORT", "8082"));

        Migrate.run();
        PayDb.bootstrap();
        seedDemoCatalog();

        PayApi.start(payPort);
        MartApi.start(martPort);

        System.out.println("minipay  (processor) up: http://localhost:" + payPort + "/v1/payment_intents");
        System.out.println("minimart (merchant)  up: http://localhost:" + martPort + "/api/catalog");
        Thread.currentThread().join();
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
