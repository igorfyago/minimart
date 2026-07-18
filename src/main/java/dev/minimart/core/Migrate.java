package dev.minimart.core;

import org.flywaydb.core.Flyway;

/** The schema is versioned SQL under db/, applied at boot. */
public final class Migrate {

    private Migrate() {}

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
