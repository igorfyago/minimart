package dev.minimart.core;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Raw JDBC, through a hand-written pool. See Pool for why the naive
 * connection-per-call version had to go: it ran the machine out of ephemeral
 * ports under a few hundred simulated customers, and took reproducibility with it.
 */
public final class Db {

    public static final String URL =
            env("MINIMART_DB_URL", "jdbc:postgresql://localhost:5436/minimart");
    public static final String USER = env("MINIMART_DB_USER", "minimart");
    public static final String PASSWORD = env("MINIMART_DB_PASSWORD", "minimart");

    private static final Pool POOL = new Pool(URL, USER, PASSWORD,
            Integer.parseInt(env("MINIMART_POOL", "16")));

    private Db() {}

    public static Connection open() throws SQLException {
        return POOL.borrow(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** A real physical connection, for bootstrap work that must not be pooled. */
    public static Connection openPhysical() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    public static Pool pool() { return POOL; }

    private static String env(String k, String fallback) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? fallback : v;
    }
}
