package dev.minimart.core;

import org.flywaydb.core.Flyway;

/** The schema is versioned SQL under db/, applied at boot. */
public final class Migrate {

    private Migrate() {}

    /** Create the database if it is missing, then migrate. Lets the app be
     *  pointed at a bare Postgres server and bring itself up. */
    public static void bootstrap() throws java.sql.SQLException {
        String admin = System.getenv().getOrDefault("MINIMART_ADMIN_URL", "");
        if (!admin.isBlank()) {
            String name = Db.URL.substring(Db.URL.lastIndexOf('/') + 1);
            try (java.sql.Connection c = java.sql.DriverManager.getConnection(admin, Db.USER, Db.PASSWORD);
                 java.sql.Statement st = c.createStatement();
                 java.sql.ResultSet rs = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + name + "'")) {
                if (!rs.next()) {
                    try (java.sql.Statement create = c.createStatement()) {
                        create.execute("CREATE DATABASE " + name);
                    }
                }
            }
        }
        run();
    }

    public static void run() {
        Flyway.configure()
                .dataSource(Db.URL, Db.USER, Db.PASSWORD)
                .locations("classpath:db")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }
}
