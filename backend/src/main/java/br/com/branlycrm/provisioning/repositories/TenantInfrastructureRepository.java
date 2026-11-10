package br.com.branlycrm.provisioning.repositories;

import br.com.branlycrm.provisioning.exceptions.ProvisioningException;
import br.com.branlycrm.tenant.domain.TenantDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;

/**
 * DDL executed with the provisioner credential (CREATEDB/CREATEROLE, never superuser).
 *
 * <p>Identifiers cannot be JDBC parameters, so every name is derived from the tenant UUID by {@link
 * TenantDatabase}, revalidated here and double-quoted. Each method is idempotent: existing objects
 * are reused only after checking they are the ones this provisioner created.
 *
 * <p>Per clinic: {@code owner_<id>} (NOLOGIN) owns the database and every object; {@code
 * tenant_<id>} (LOGIN) is the runtime role. The provisioner holds SET, not INHERIT, on the owner
 * role, so it acts as owner only for explicit DDL and migrations.
 */
public class TenantInfrastructureRepository {
  private static final Pattern NAME = Pattern.compile("(owner|tenant|clinic)_[a-f0-9]{32}");
  private static final Pattern PASSWORD = Pattern.compile("[\\x21-\\x7E]{16,256}");
  private static final int RUNTIME_CONNECTION_LIMIT = 20;
  private static final String LOCK_TIMEOUT = "10s";

  private final String host;
  private final int port;
  private final String maintenanceDatabase;
  private final String username;
  private final String password;

  public TenantInfrastructureRepository(
      String host, int port, String maintenanceDatabase, String username, String password) {
    if (!host.matches("[a-zA-Z0-9.-]+") || port < 1 || port > 65535) {
      throw new IllegalArgumentException("Invalid tenant database endpoint");
    }
    if (!maintenanceDatabase.matches("[a-z_][a-z0-9_]{0,62}") || username.isBlank()) {
      throw new IllegalArgumentException("Invalid provisioner configuration");
    }
    if (password == null || password.isBlank()) {
      throw new IllegalStateException("Provisioner credential is not configured");
    }
    this.host = host;
    this.port = port;
    this.maintenanceDatabase = maintenanceDatabase;
    this.username = username;
    this.password = password;
  }

  public static String ownerRole(TenantDatabase database) {
    return "owner_" + database.databaseUser().substring("tenant_".length());
  }

  public void ensureRoles(TenantDatabase database, String runtimePassword) {
    if (runtimePassword == null || !PASSWORD.matcher(runtimePassword).matches()) {
      throw new ProvisioningException("SECRET_INVALID");
    }
    String owner = name(ownerRole(database));
    String runtime = name(database.databaseUser());
    try (Connection connection = connect(maintenanceDatabase);
        Statement statement = connection.createStatement()) {
      String provisioner = quote(sessionUser(connection));
      if (!roleExists(connection, owner)) {
        statement.execute("CREATE ROLE " + quote(owner) + " NOLOGIN NOINHERIT");
      }
      requireOwnRole(connection, owner, false);
      statement.execute(
          "GRANT " + quote(owner) + " TO " + provisioner + " WITH INHERIT FALSE, SET TRUE");
      String attributes =
          " LOGIN NOINHERIT NOCREATEDB NOCREATEROLE CONNECTION LIMIT "
              + RUNTIME_CONNECTION_LIMIT
              + " PASSWORD "
              + literal(runtimePassword);
      if (roleExists(connection, runtime)) {
        // Reconcile with the stored secret: a retry after a crash must not leave them diverged.
        // SUPERUSER/REPLICATION/BYPASSRLS can only be altered by a superuser; requireOwnRole
        // below rejects the role if any of them is set.
        statement.execute("ALTER ROLE " + quote(runtime) + attributes);
      } else {
        statement.execute(
            "CREATE ROLE "
                + quote(runtime)
                + attributes
                + " NOSUPERUSER NOREPLICATION NOBYPASSRLS");
      }
      requireOwnRole(connection, runtime, true);
    } catch (SQLException exception) {
      throw new ProvisioningException("ROLES_FAILED", exception);
    }
  }

  public void ensureDatabase(TenantDatabase database) {
    String clinic = name(database.databaseName());
    String owner = name(ownerRole(database));
    String runtime = name(database.databaseUser());
    try (Connection connection = connect(maintenanceDatabase);
        Statement statement = connection.createStatement()) {
      String provisioner = quote(sessionUser(connection));
      String currentOwner = databaseOwner(connection, clinic);
      if (currentOwner == null) {
        // CREATE DATABASE cannot run inside a transaction; the connection is in autocommit.
        statement.execute(
            "CREATE DATABASE " + quote(clinic) + " OWNER " + quote(owner) + " TEMPLATE template1");
      } else if (!owner.equals(currentOwner)) {
        throw new ProvisioningException("DATABASE_CONFLICT");
      }
      statement.execute("SET ROLE " + quote(owner));
      statement.execute("REVOKE ALL ON DATABASE " + quote(clinic) + " FROM PUBLIC");
      statement.execute(
          "GRANT CONNECT ON DATABASE "
              + quote(clinic)
              + " TO "
              + quote(runtime)
              + ", "
              + provisioner);
      statement.execute("RESET ROLE");
    } catch (SQLException exception) {
      throw new ProvisioningException("DATABASE_FAILED", exception);
    }
  }

  /**
   * Read-only proof that an existing clinic is the one this provisioner created, before the rollout
   * migrates it (ADR-031): the database exists and belongs to {@code owner_<id>}, both roles pass
   * the same checks as {@link #ensureRoles}, and the database declares this clinic. Nothing is
   * created, granted or altered here.
   */
  public void requireProvisioned(TenantDatabase database) {
    String clinic = name(database.databaseName());
    String owner = name(ownerRole(database));
    String runtime = name(database.databaseUser());
    try (Connection connection = connect(maintenanceDatabase)) {
      String currentOwner = databaseOwner(connection, clinic);
      if (currentOwner == null) throw new ProvisioningException("DATABASE_MISSING");
      if (!owner.equals(currentOwner)) throw new ProvisioningException("DATABASE_CONFLICT");
      requireOwnRole(connection, owner, false);
      requireOwnRole(connection, runtime, true);
    } catch (SQLException exception) {
      throw new ProvisioningException("PRECHECK_FAILED", exception);
    }
    try (Connection connection = connect(clinic);
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE " + quote(owner));
      try (var check =
          connection.prepareStatement(
              "SELECT count(*) = 1 AND bool_and(tenant_id = ?::uuid) FROM tenant_identity")) {
        check.setString(1, database.tenantId().toString());
        try (var rows = check.executeQuery()) {
          if (!rows.next() || !rows.getBoolean(1)) {
            throw new ProvisioningException("IDENTITY_MISMATCH");
          }
        }
      }
    } catch (SQLException exception) {
      throw new ProvisioningException("PRECHECK_FAILED", exception);
    }
  }

  /**
   * Applies db/tenant-migration as the owner role; the runtime role never migrates. Returns how
   * many migrations were applied now (0 when the clinic was already up to date).
   */
  public int migrate(TenantDatabase database) {
    String clinic = name(database.databaseName());
    String owner = name(ownerRole(database));
    int applied;
    try {
      applied =
          Flyway.configure()
              // Same as connect(): no server error detail (row values) in the cause.
              .dataSource(url(clinic) + "?logServerErrorDetail=false", username, password)
              // lock_timeout: a DDL waiting behind API transactions gives up instead of queueing
              // every later query of the clinic behind its lock request.
              .initSql("SET ROLE " + quote(owner) + "; SET lock_timeout = '" + LOCK_TIMEOUT + "'")
              .locations("classpath:db/tenant-migration")
              .placeholders(
                  Map.of(
                      "tenantId", database.tenantId().toString(),
                      "runtimeRole", name(database.databaseUser())))
              .cleanDisabled(true)
              .validateMigrationNaming(true)
              .load()
              .migrate()
              .migrationsExecuted;
    } catch (FlywayException exception) {
      throw new ProvisioningException("MIGRATION_FAILED", exception);
    }
    try (Connection connection = connect(clinic);
        var check =
            connection.prepareStatement(
                """
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'v', 'm', 'S')
                  AND pg_get_userbyid(c.relowner) <> ?
                """)) {
      check.setString(1, owner);
      try (var rows = check.executeQuery()) {
        if (!rows.next() || rows.getLong(1) != 0) {
          throw new ProvisioningException("OWNERSHIP_CONFLICT");
        }
      }
    } catch (SQLException exception) {
      throw new ProvisioningException("MIGRATION_FAILED", exception);
    }
    return applied;
  }

  private void requireOwnRole(Connection connection, String role, boolean login)
      throws SQLException {
    try (var check =
        connection.prepareStatement(
            """
            SELECT r.rolcanlogin, r.rolsuper OR r.rolcreatedb OR r.rolcreaterole
                   OR r.rolreplication OR r.rolbypassrls AS elevated,
                   EXISTS (SELECT 1 FROM pg_auth_members a
                           WHERE a.roleid = r.oid AND a.member = (SELECT oid FROM pg_roles
                                                                  WHERE rolname = session_user)
                             AND a.admin_option) AS administered,
                   EXISTS (SELECT 1 FROM pg_auth_members a WHERE a.member = r.oid) AS member
            FROM pg_roles r WHERE r.rolname = ?
            """)) {
      check.setString(1, role);
      try (var rows = check.executeQuery()) {
        // PostgreSQL 16+ grants ADMIN on created roles to their creator: it proves ownership.
        if (!rows.next()
            || rows.getBoolean("rolcanlogin") != login
            || rows.getBoolean("elevated")
            || !rows.getBoolean("administered")
            || rows.getBoolean("member")) {
          throw new ProvisioningException("ROLE_CONFLICT");
        }
      }
    }
  }

  private static boolean roleExists(Connection connection, String role) throws SQLException {
    try (var check = connection.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?")) {
      check.setString(1, role);
      try (var rows = check.executeQuery()) {
        return rows.next();
      }
    }
  }

  private static String databaseOwner(Connection connection, String database) throws SQLException {
    try (var check =
        connection.prepareStatement(
            "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = ?")) {
      check.setString(1, database);
      try (var rows = check.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    }
  }

  private static String sessionUser(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT session_user")) {
      rows.next();
      return rows.getString(1);
    }
  }

  private Connection connect(String database) throws SQLException {
    Properties properties = new Properties();
    properties.setProperty("user", username);
    properties.setProperty("password", password);
    properties.setProperty("connectTimeout", "5");
    properties.setProperty("logServerErrorDetail", "false");
    Connection connection = DriverManager.getConnection(url(database), properties);
    connection.setAutoCommit(true);
    return connection;
  }

  private String url(String database) {
    return "jdbc:postgresql://" + host + ":" + port + "/" + database;
  }

  private static String name(String value) {
    if (!NAME.matcher(value).matches()) throw new ProvisioningException("INVALID_NAME");
    return value;
  }

  private static String quote(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  /** standard_conforming_strings is on by default: only single quotes need doubling. */
  private static String literal(String value) {
    return "'" + value.replace("'", "''") + "'";
  }
}
