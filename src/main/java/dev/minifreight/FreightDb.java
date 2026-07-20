package dev.minifreight;

import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * minifreight's own database, for the same reason minipay has one: the party
 * that hands parcels to carriers is a different trust domain from the merchant
 * that sold them. Everything freight believes about an order it learned from
 * an event, and everything it knows about a parcel it learned from a carrier,
 * and neither of those is a table anybody else can read.
 */
public final class FreightDb {

    private static final String ADMIN_URL =
            env("MINIFREIGHT_ADMIN_URL", "jdbc:postgresql://localhost:5436/minimart");
    public static final String URL =
            env("MINIFREIGHT_DB_URL", "jdbc:postgresql://localhost:5436/minifreight");
    public static final String USER = env("MINIFREIGHT_DB_USER", "minimart");
    public static final String PASSWORD = env("MINIFREIGHT_DB_PASSWORD", "minimart");

    private static final dev.minimart.core.Pool POOL =
            new dev.minimart.core.Pool(URL, USER, PASSWORD,
                    Integer.parseInt(env("MINIFREIGHT_POOL", "16")));

    private FreightDb() {}

    public static Connection open() throws SQLException {
        return POOL.borrow(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Create the database if it is missing, then migrate it. */
    public static void bootstrap() throws SQLException {
        try (Connection c = DriverManager.getConnection(ADMIN_URL, USER, PASSWORD);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = 'minifreight'")) {
            if (!rs.next()) {
                try (Statement create = c.createStatement()) { create.execute("CREATE DATABASE minifreight"); }
            }
        }
        Flyway.configure().dataSource(URL, USER, PASSWORD)
                .locations("classpath:db_freight")
                .baselineOnMigrate(true).baselineVersion("0")
                .load().migrate();
    }

    private static String env(String k, String fallback) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? fallback : v;
    }
}
