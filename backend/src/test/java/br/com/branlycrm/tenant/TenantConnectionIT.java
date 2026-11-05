package br.com.branlycrm.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.support.TestPostgres;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.repositories.TenantConnectionRepository;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Connects to real per-clinic databases with their own login role and password over TCP.
 * Cross-credential isolation (A cannot reach B) belongs to the provisioner and is not proven here.
 */
@Testcontainers
@SpringBootTest(properties = "spring.docker.compose.enabled=false")
class TenantConnectionIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final UUID CLINIC = UUID.randomUUID();
  private static final UUID MISMATCHED = UUID.randomUUID();
  private static final String SECRET = "synthetic-" + UUID.randomUUID();

  @Autowired JdbcTemplate jdbc;
  @Autowired TenantConnectionRepository connections;

  @DynamicPropertySource
  static void tenantDatabase(DynamicPropertyRegistry registry) {
    registry.add("app.tenancy.host", POSTGRES::getHost);
    registry.add("app.tenancy.port", () -> POSTGRES.getMappedPort(5432));
    registry.add(secretReference(CLINIC), () -> SECRET);
    registry.add(secretReference(MISMATCHED), () -> SECRET);
  }

  @BeforeAll
  static void provisionDisposableClinicDatabases() throws SQLException {
    provision(CLINIC, CLINIC);
    // Registry says MISMATCHED, but its database declares another clinic's identity.
    provision(MISMATCHED, UUID.randomUUID());
  }

  @Test
  void activeMemberReachesOnlyTheClinicDatabaseWithItsRuntimeRole() throws SQLException {
    UUID subject = activeMember(CLINIC);
    try (var connection = connections.open(CLINIC, subject);
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT current_user, current_database()")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString(1)).isEqualTo("tenant_" + suffix(CLINIC));
      assertThat(rows.getString(2)).isEqualTo("clinic_" + suffix(CLINIC));
    }
  }

  @Test
  void identityMismatchFailsClosed() {
    UUID subject = activeMember(MISMATCHED);
    assertThatThrownBy(() -> connections.open(MISMATCHED, subject))
        .isInstanceOf(SQLException.class)
        .hasMessage("Tenant database identity mismatch");
  }

  @Test
  void subjectWithoutMembershipIsDeniedBeforeConnecting() {
    activeMember(CLINIC);
    assertThatThrownBy(() -> connections.open(CLINIC, UUID.randomUUID()))
        .isInstanceOf(TenantAccessException.class);
  }

  private UUID activeMember(UUID tenant) {
    String suffix = suffix(tenant);
    jdbc.update(
        "INSERT INTO tenants (tenant_id, clinic_name, database_name, database_user,"
            + " credential_reference, status) VALUES (?, 'Clínica', ?, ?, ?, 'ACTIVE')"
            + " ON CONFLICT (tenant_id) DO NOTHING",
        tenant,
        "clinic_" + suffix,
        "tenant_" + suffix,
        secretReference(tenant));
    UUID subject = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO user_accounts (subject_id, email, display_name, password_hash, email_verified)"
            + " VALUES (?, ?, 'Synthetic', '{bcrypt}synthetic', true)",
        subject,
        subject + "@example.test");
    jdbc.update(
        "INSERT INTO tenant_memberships (tenant_id, subject_id, role) VALUES (?, ?, 'ADMIN')",
        tenant,
        subject);
    return subject;
  }

  /** Test-only provisioning with the container superuser; names derive from generated UUIDs. */
  private static void provision(UUID tenant, UUID declaredIdentity) throws SQLException {
    String suffix = suffix(tenant);
    try (var admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = admin.createStatement()) {
      statement.execute("CREATE ROLE tenant_" + suffix + " LOGIN PASSWORD '" + SECRET + "'");
      statement.execute("CREATE DATABASE clinic_" + suffix);
      statement.execute("REVOKE CONNECT ON DATABASE clinic_" + suffix + " FROM PUBLIC");
      statement.execute("GRANT CONNECT ON DATABASE clinic_" + suffix + " TO tenant_" + suffix);
    }
    String url = POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/clinic_" + suffix + "$1");
    Flyway.configure()
        .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/tenant-migration")
        .load()
        .migrate();
    try (var admin =
            DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = admin.createStatement()) {
      statement.execute(
          "INSERT INTO tenant_identity (tenant_id) VALUES ('" + declaredIdentity + "')");
      statement.execute("GRANT SELECT ON tenant_identity TO tenant_" + suffix);
    }
  }

  private static String secretReference(UUID tenant) {
    return "TENANT_SECRET_" + suffix(tenant).toUpperCase(Locale.ROOT);
  }

  private static String suffix(UUID tenant) {
    return tenant.toString().replace("-", "");
  }
}
