package dev.minimart.core;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Raw JDBC. One connection per call for now, exactly as minibank stage 0 did,
 * so the cost is felt before the pool is written.
 */
public final class Db {

    public static final String URL =
            env("MINIMART_DB_URL", "jdbc:postgresql://localhost:5436/minimart");
    public static final String USER = env("MINIMART_DB_USER", "minimart");
    public static final String PASSWORD = env("MINIMART_DB_PASSWORD", "minimart");

    private Db() {}

    public static Connection open() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    private static String env(String k, String fallback) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? fallback : v;
    }
}
