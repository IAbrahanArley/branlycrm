package br.com.branlycrm.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Disposable PostgreSQL 17 with pgvector, pinned by digest. Never the developer database. */
public final class TestPostgres {
  public static final String PROVISIONER = "crm_tenant_provisioner";
  public static final String WORKER = "crm_provisioning_worker";
  public static final String RUNTIME = "crm_control_runtime";
  public static final String PASSWORD = "synthetic-role-password-2026";

  private static final DockerImageName IMAGE =
      DockerImageName.parse(
              "pgvector/pgvector@sha256:7d0a41464797aeb9862dfdc0bbf1526b82cfd324cdd8036df19e3545f4a9ccb8")
          .asCompatibleSubstituteFor("postgres");

  private TestPostgres() {}

  public static PostgreSQLContainer container() {
    return new PostgreSQLContainer(IMAGE);
  }

  /**
   * Mirrors infra/postgres/bootstrap-roles.sh: deployment roles without superuser, PUBLIC without
   * CONNECT on control and maintenance databases, and vector prepared in template1 so clinic
   * databases never need a superuser. Keep both in sync.
   */
  public static void bootstrapRoles(PostgreSQLContainer postgres) throws SQLException {
    String control = postgres.getDatabaseName();
    try (Connection admin = admin(postgres, control);
        Statement sql = admin.createStatement()) {
      sql.execute(
          "CREATE ROLE "
              + PROVISIONER
              + " LOGIN NOINHERIT CREATEDB CREATEROLE PASSWORD '"
              + PASSWORD
              + "'");
      sql.execute("CREATE ROLE " + WORKER + " LOGIN NOINHERIT PASSWORD '" + PASSWORD + "'");
      sql.execute("CREATE ROLE " + RUNTIME + " LOGIN NOINHERIT PASSWORD '" + PASSWORD + "'");
      sql.execute("REVOKE ALL ON DATABASE " + control + " FROM PUBLIC");
      sql.execute("GRANT CONNECT ON DATABASE " + control + " TO " + RUNTIME + ", " + WORKER);
      sql.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
      sql.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME + ", " + WORKER);
      sql.execute("REVOKE ALL ON DATABASE postgres FROM PUBLIC");
      sql.execute("GRANT CONNECT ON DATABASE postgres TO " + PROVISIONER);
      sql.execute("REVOKE ALL ON DATABASE template1 FROM PUBLIC");
    }
    try (Connection template = admin(postgres, "template1");
        Statement sql = template.createStatement()) {
      sql.execute("CREATE EXTENSION IF NOT EXISTS vector");
    }
  }

  public static Connection admin(PostgreSQLContainer postgres, String database)
      throws SQLException {
    return DriverManager.getConnection(
        url(postgres, database), postgres.getUsername(), postgres.getPassword());
  }

  public static String url(PostgreSQLContainer postgres, String database) {
    return "jdbc:postgresql://"
        + postgres.getHost()
        + ":"
        + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        + "/"
        + database;
  }
}
