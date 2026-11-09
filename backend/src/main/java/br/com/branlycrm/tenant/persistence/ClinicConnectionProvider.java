package br.com.branlycrm.tenant.persistence;

import br.com.branlycrm.tenant.repositories.TenantConnectionRepository;
import java.sql.Connection;
import java.sql.SQLException;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;

/**
 * Gives Hibernate clinic connections only through {@link TenantConnectionRepository#open}, so every
 * session re-checks the active membership, reads the clinic secret and verifies tenant_identity.
 * There is no connection without a tenant: schema tools and metadata lookups that ask for "any"
 * connection fail.
 */
final class ClinicConnectionProvider implements MultiTenantConnectionProvider<ClinicTenant> {
  private final transient TenantConnectionRepository connections;

  ClinicConnectionProvider(TenantConnectionRepository connections) {
    this.connections = connections;
  }

  @Override
  public Connection getAnyConnection() throws SQLException {
    throw new SQLException("Clinic connections require an explicit tenant");
  }

  @Override
  public void releaseAnyConnection(Connection connection) throws SQLException {
    connection.close();
  }

  @Override
  public Connection getConnection(ClinicTenant tenant) throws SQLException {
    if (tenant == null) throw new SQLException("Clinic connections require an explicit tenant");
    return connections.open(tenant.tenantId(), tenant.subjectId());
  }

  @Override
  public void releaseConnection(ClinicTenant tenant, Connection connection) throws SQLException {
    connection.close();
  }

  @Override
  public boolean supportsAggressiveRelease() {
    return false;
  }

  @Override
  public boolean isUnwrappableAs(Class<?> type) {
    return false;
  }

  @Override
  public <T> T unwrap(Class<T> type) {
    throw new UnsupportedOperationException("Clinic connection provider is not unwrappable");
  }
}
