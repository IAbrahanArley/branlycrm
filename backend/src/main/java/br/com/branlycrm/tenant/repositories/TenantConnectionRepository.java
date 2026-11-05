package br.com.branlycrm.tenant.repositories;

import br.com.branlycrm.tenant.domain.TenantDatabase;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Repository;

/**
 * Opens a connection to one clinic's own database (ADR-014). Authorization is checked here so no
 * caller can reach a clinic database without an active membership. There is no fallback to the
 * control database: missing secret, inactive clinic or identity mismatch all fail closed.
 *
 * <p>Connection per operation is provisional; bounded pools come with tenant JPA
 * (plano-isolamento-clinicas.md).
 */
@Repository
public class TenantConnectionRepository {
  private final TenantRegistryRepository registry;
  private final Environment environment;
  private final String host;
  private final int port;

  public TenantConnectionRepository(
      TenantRegistryRepository registry,
      Environment environment,
      @Value("${app.tenancy.host:localhost}") String host,
      @Value("${app.tenancy.port:5432}") int port) {
    if (!host.matches("[a-zA-Z0-9.-]+") || port < 1 || port > 65535) {
      throw new IllegalArgumentException("Invalid tenant database endpoint");
    }
    this.registry = registry;
    this.environment = environment;
    this.host = host;
    this.port = port;
  }

  /**
   * The explicit tenant argument avoids thread-local leakage across requests and workers. The
   * caller owns the connection and must close it with try-with-resources.
   */
  public Connection open(UUID tenantId, UUID authenticatedSubject) throws SQLException {
    if (tenantId == null || authenticatedSubject == null) throw new TenantAccessException();
    TenantDatabase database =
        registry
            .findAuthorized(tenantId, authenticatedSubject)
            .orElseThrow(TenantAccessException::new);
    String password = environment.getProperty(database.credentialReference());
    if (password == null || password.isBlank()) {
      throw new IllegalStateException("Tenant credentials unavailable");
    }
    Properties properties = new Properties();
    properties.setProperty("user", database.databaseUser());
    properties.setProperty("password", password);
    properties.setProperty("connectTimeout", "5");
    Connection connection =
        DriverManager.getConnection(
            "jdbc:postgresql://" + host + ":" + port + "/" + database.databaseName(), properties);
    try {
      verifyIdentity(connection, tenantId);
      return connection;
    } catch (SQLException | RuntimeException error) {
      try {
        connection.close();
      } catch (SQLException closeError) {
        error.addSuppressed(closeError);
      }
      throw error;
    }
  }

  /** The connected database must declare exactly the expected tenant in tenant_identity. */
  static void verifyIdentity(Connection connection, UUID tenantId) throws SQLException {
    try (var statement =
        connection.prepareStatement("SELECT tenant_id FROM tenant_identity WHERE singleton")) {
      statement.setQueryTimeout(5);
      try (var rows = statement.executeQuery()) {
        if (!rows.next() || !tenantId.equals(rows.getObject(1, UUID.class)) || rows.next()) {
          throw new SQLException("Tenant database identity mismatch");
        }
      }
    }
  }
}
