package dev.minianalytics;

import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Analytics keeps its own books, in its own database.
 *
 * Three services, three databases now: minimart owns goods and orders, minipay
 * owns payments, minianalytics owns what any of it MEANT. They share a Postgres
 * server here because one small machine is paying for all of this, but they
 * share no schema, no transaction and no table, so moving one onto its own
 * server later is a change of URL and nothing else.
 */
public final class AnalyticsDb {

    private static final String ADMIN_URL =
            env("ANALYTICS_ADMIN_URL", "jdbc:postgresql://localhost:5436/minimart");
    public static final String URL =
            env("ANALYTICS_DB_URL", "jdbc:postgresql://localhost:5436/minimart_analytics");
    public static final String USER = env("ANALYTICS_DB_USER", "minimart");
    public static final String PASSWORD = env("ANALYTICS_DB_PASSWORD", "minimart");

    private static final dev.minimart.core.Pool POOL =
            new dev.minimart.core.Pool(URL, USER, PASSWORD, Integer.parseInt(env("ANALYTICS_POOL", "8")));

    private AnalyticsDb() {}

    public static Connection open() throws SQLException {
        return POOL.borrow(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    public static void bootstrap() throws SQLException {
        try (Connection c = DriverManager.getConnection(ADMIN_URL, USER, PASSWORD);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = 'minimart_analytics'")) {
            if (!rs.next()) {
                try (Statement create = c.createStatement()) {
                    create.execute("CREATE DATABASE minimart_analytics");
                }
            }
        }
        Flyway.configure().dataSource(URL, USER, PASSWORD)
                .locations("classpath:db_analytics")
                .baselineOnMigrate(true).baselineVersion("0")
                .load().migrate();
    }

    private static String env(String k, String fallback) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? fallback : v;
    }
}
