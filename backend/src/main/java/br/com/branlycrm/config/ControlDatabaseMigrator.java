package br.com.branlycrm.config;

import org.flywaydb.core.Flyway;

/** Applies control database migrations outside the HTTP process (ADR-016). */
public final class ControlDatabaseMigrator {
  private ControlDatabaseMigrator() {}

  public static void migrate() {
    Flyway.configure()
        .dataSource(
            required("MIGRATION_DB_URL"),
            required("MIGRATION_DB_USER"),
            required("MIGRATION_DB_PASSWORD"))
        .locations("classpath:db/migration")
        .cleanDisabled(true)
        .load()
        .migrate();
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing configuration: " + name);
    }
    return value;
  }
}
