package br.com.branlycrm.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.support.TestPostgres;
import br.com.branlycrm.tenant.domain.TenantDatabase;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.repositories.TenantConnectionRepository;
import br.com.branlycrm.tenant.secrets.FileTenantSecretWriter;
import br.com.branlycrm.tenant.secrets.TenantSecretException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
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
 * Connects to real per-clinic databases with their own login role over TCP, reading the password
 * from files published by the provisioner's writer. Cross-credential isolation (A cannot reach B)
 * belongs to the provisioner and is not proven here.
 */
@Testcontainers
@SpringBootTest(properties = "spring.docker.compose.enabled=false")
class TenantConnectionIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final UUID CLINIC = UUID.randomUUID();
  private static final UUID MISMATCHED = UUID.randomUUID();
  private static final UUID WITHOUT_SECRET = UUID.randomUUID();
  private static final UUID ROTATED = UUID.randomUUID();
  private static final String SECRET = "synthetic-" + UUID.randomUUID();
  private static final Path SECRETS = temporaryDirectory();

  @Autowired JdbcTemplate jdbc;
  @Autowired TenantConnectionRepository connections;

  @DynamicPropertySource
  static void tenantDatabase(DynamicPropertyRegistry registry) {
    registry.add("app.tenancy.host", POSTGRES::getHost);
    registry.add("app.tenancy.port", () -> POSTGRES.getMappedPort(5432));
    registry.add("app.tenancy.secrets.directory", SECRETS::toString);
  }

  @BeforeAll
  static void provisionDisposableClinicDatabases() throws SQLException {
    provision(CLINIC, CLINIC);
    // Registry says MISMATCHED, but its database declares another clinic's identity.
    provision(MISMATCHED, UUID.randomUUID());
    provision(WITHOUT_SECRET, WITHOUT_SECRET);
    provision(ROTATED, ROTATED);
    var writer = new FileTenantSecretWriter(SECRETS.toString());
    for (UUID tenant : new UUID[] {CLINIC, MISMATCHED, ROTATED}) {
      writer.create(database(tenant), SECRET);
    }
    // The file now holds a password the clinic role does not have.
    writer.rotate(database(ROTATED), 1, "synthetic-" + UUID.randomUUID());
  }

  @AfterAll
  static void removeTheSecretDirectoryThisClassCreated() throws IOException {
    try (var files = Files.list(SECRETS)) {
      for (Path file : files.toList()) Files.delete(file);
    }
    Files.delete(SECRETS);
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
  void missingSecretFailsClosedWithoutFallback() {
    UUID subject = activeMember(WITHOUT_SECRET);
    assertThatThrownBy(() -> connections.open(WITHOUT_SECRET, subject))
        .isInstanceOf(TenantSecretException.class)
        .hasMessage("Tenant credentials unavailable");
  }

  @Test
  void passwordComesFromTheSecretStore() {
    UUID subject = activeMember(ROTATED);
    assertThatThrownBy(() -> connections.open(ROTATED, subject))
        .isInstanceOf(SQLException.class)
        .hasMessageNotContaining(SECRET);
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

  private static TenantDatabase database(UUID tenant) {
    String suffix = suffix(tenant);
    return new TenantDatabase(
        tenant, "clinic_" + suffix, "tenant_" + suffix, secretReference(tenant));
  }

  private static Path temporaryDirectory() {
    try {
      return Files.createTempDirectory("branly-tenant-secrets-");
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String secretReference(UUID tenant) {
    return "TENANT_SECRET_" + suffix(tenant).toUpperCase(Locale.ROOT);
  }

  private static String suffix(UUID tenant) {
    return tenant.toString().replace("-", "");
  }
}
