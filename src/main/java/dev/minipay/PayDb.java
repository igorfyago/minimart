package dev.minipay;

import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * minipay's own database. A payment processor is a different trust domain from
 * the merchant it serves, so it gets its own books and its own connection. The
 * only thing crossing between them is HTTP.
 */
public final class PayDb {

    private static final String ADMIN_URL =
            env("MINIPAY_ADMIN_URL", "jdbc:postgresql://localhost:5436/minimart");
    public static final String URL =
            env("MINIPAY_DB_URL", "jdbc:postgresql://localhost:5436/minipay");
    public static final String USER = env("MINIPAY_DB_USER", "minimart");
    public static final String PASSWORD = env("MINIPAY_DB_PASSWORD", "minimart");

    // the processor pools its connections too · same lesson, same reason
    private static final dev.minimart.core.Pool POOL =
            new dev.minimart.core.Pool(URL, USER, PASSWORD,
                    Integer.parseInt(env("MINIPAY_POOL", "16")));

    private PayDb() {}

    public static Connection open() throws SQLException {
        return POOL.borrow(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Create the database if it is missing, then migrate it. */
    public static void bootstrap() throws SQLException {
        try (Connection c = DriverManager.getConnection(ADMIN_URL, USER, PASSWORD);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = 'minipay'")) {
            if (!rs.next()) {
                try (Statement create = c.createStatement()) { create.execute("CREATE DATABASE minipay"); }
            }
        }
        Flyway.configure().dataSource(URL, USER, PASSWORD)
                .locations("classpath:db_pay")
                .baselineOnMigrate(true).baselineVersion("0")
                .load().migrate();
    }

    private static String env(String k, String fallback) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? fallback : v;
    }
}
