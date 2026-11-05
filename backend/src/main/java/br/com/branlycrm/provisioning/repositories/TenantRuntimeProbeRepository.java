package br.com.branlycrm.provisioning.repositories;

import br.com.branlycrm.provisioning.exceptions.ProvisioningException;
import br.com.branlycrm.tenant.domain.TenantDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;

/**
 * Logs in with the clinic runtime credential over TCP and checks what it can actually do. Using SET
 * ROLE as an administrator would not exercise authentication or CONNECT privileges.
 */
public class TenantRuntimeProbeRepository {
  /** PostgreSQL insufficient_privilege: CONNECT denied for the database. */
  public static final String CONNECT_DENIED = "42501";

  private final String host;
  private final int port;

  public TenantRuntimeProbeRepository(String host, int port) {
    if (!host.matches("[a-zA-Z0-9.-]+") || port < 1 || port > 65535) {
      throw new IllegalArgumentException("Invalid tenant database endpoint");
    }
    this.host = host;
    this.port = port;
  }

  /**
   * The runtime role reads its own identity and nothing else of the schema, cannot create objects,
   * holds no role memberships or elevated attributes, and cannot connect to any database in {@code
   * forbiddenDatabases}.
   */
  public void verify(TenantDatabase database, String password, List<String> forbiddenDatabases) {
    try (Connection connection = connect(database.databaseName(), database, password);
        var check =
            connection.prepareStatement(
                """
                SELECT current_user = ? AND current_database() = ?
                   AND (SELECT count(*) FROM tenant_identity) = 1
                   AND (SELECT tenant_id FROM tenant_identity) = ?::uuid
                   AND NOT has_table_privilege('tenant_identity', 'INSERT,UPDATE,DELETE,TRUNCATE')
                   AND NOT has_table_privilege('flyway_schema_history', 'SELECT,INSERT,UPDATE,DELETE')
                   AND NOT has_schema_privilege('public', 'CREATE')
                   AND NOT has_database_privilege(current_database(), 'CREATE,TEMPORARY')
                   AND NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = current_user
                                   AND (rolsuper OR rolcreatedb OR rolcreaterole
                                        OR rolreplication OR rolbypassrls OR rolinherit))
                   AND NOT EXISTS (SELECT 1 FROM pg_auth_members
                                   WHERE member = (SELECT oid FROM pg_roles
                                                   WHERE rolname = current_user))
                """)) {
      check.setString(1, database.databaseUser());
      check.setString(2, database.databaseName());
      check.setString(3, database.tenantId().toString());
      try (var rows = check.executeQuery()) {
        if (!rows.next() || !rows.getBoolean(1)) {
          throw new ProvisioningException("VERIFICATION_FAILED");
        }
      }
    } catch (SQLException exception) {
      throw new ProvisioningException("VERIFICATION_FAILED", exception);
    }
    for (String forbidden : forbiddenDatabases) {
      if (canConnect(forbidden, database, password)) {
        throw new ProvisioningException("VERIFICATION_FAILED");
      }
    }
  }

  /** True only if login succeeds; CONNECT denial is the expected outcome for foreign databases. */
  public boolean canConnect(String target, TenantDatabase database, String password) {
    try (Connection ignored = connect(target, database, password)) {
      return true;
    } catch (SQLException exception) {
      if (CONNECT_DENIED.equals(exception.getSQLState())) return false;
      throw new ProvisioningException("VERIFICATION_FAILED", exception);
    }
  }

  private Connection connect(String target, TenantDatabase database, String password)
      throws SQLException {
    if (!target.matches("[a-z_][a-z0-9_]{0,62}")) {
      throw new ProvisioningException("INVALID_NAME");
    }
    Properties properties = new Properties();
    properties.setProperty("user", database.databaseUser());
    properties.setProperty("password", password);
    properties.setProperty("connectTimeout", "5");
    properties.setProperty("logServerErrorDetail", "false");
    return DriverManager.getConnection(
        "jdbc:postgresql://" + host + ":" + port + "/" + target, properties);
  }
}
