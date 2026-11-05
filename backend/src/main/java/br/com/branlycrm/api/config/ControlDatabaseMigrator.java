package br.com.branlycrm.api.config;
import org.flywaydb.core.Flyway;

public final class ControlDatabaseMigrator {
    private ControlDatabaseMigrator() {}
    public static void migrate() {
        Flyway.configure().dataSource(required("MIGRATION_DB_URL"),
            required("MIGRATION_DB_USER"), required("MIGRATION_DB_PASSWORD"))
            .locations("classpath:db/migration").load().migrate();
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing configuration: " + name);
        return value;
    }
}