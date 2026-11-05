package br.com.branlycrm.tenant.repositories;

import br.com.branlycrm.tenant.domain.TenantDatabase;
import br.com.branlycrm.tenant.domain.TenantSecret;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.secrets.TenantSecretException;
import br.com.branlycrm.tenant.secrets.TenantSecretReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Opens a connection to one clinic's own database (ADR-014). Authorization is checked here so no
 * caller can reach a clinic database without an active membership. There is no fallback to the
 * control database: missing secret, inactive clinic or identity mismatch all fail closed.
 *
 * <p>Connections come from {@link TenantConnectionPools}: bounded per clinic and globally, and
 * keyed by secret version so a rotated password retires the previous pool.
 */
@Repository
public class TenantConnectionRepository {
  private final TenantRegistryRepository registry;
  private final TenantSecretReader secrets;
  private final TenantConnectionPools pools;

  public TenantConnectionRepository(
      TenantRegistryRepository registry, TenantSecretReader secrets, TenantConnectionPools pools) {
    this.registry = registry;
    this.secrets = secrets;
    this.pools = pools;
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
            .orElseThrow(TenantAccessException::new)
            .database();
    TenantSecret secret =
        secrets
            .read(database)
            .orElseThrow(
                () ->
                    new TenantSecretException(
                        TenantSecretException.Reason.MISSING, "Tenant credentials unavailable"));
    Connection connection = pools.borrow(database, secret);
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
