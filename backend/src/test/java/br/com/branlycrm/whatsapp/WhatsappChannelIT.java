package br.com.branlycrm.whatsapp;

import static br.com.branlycrm.support.TestPostgres.PASSWORD;
import static br.com.branlycrm.support.TestPostgres.PROVISIONER;
import static br.com.branlycrm.support.TestPostgres.RUNTIME;
import static br.com.branlycrm.support.TestPostgres.WHATSAPP;
import static br.com.branlycrm.support.TestPostgres.WORKER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.provisioning.services.TenantProvisioningService;
import br.com.branlycrm.support.TestPostgres;
import br.com.branlycrm.tenant.domain.ChannelAccess;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.repositories.TenantConnectionPools;
import br.com.branlycrm.tenant.repositories.TenantConnectionRepository;
import br.com.branlycrm.tenant.repositories.TenantRegistryRepository;
import br.com.branlycrm.tenant.secrets.TenantSecretReader;
import br.com.branlycrm.whatsapp.repositories.ChannelRegistryRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * WhatsApp channel registry and service access to clinic databases (CRM-081a, ADR-036), with the
 * real provisioner and the deployment roles: the API cannot read sealed columns, a number and a
 * clinic have one live channel, and only an ACTIVE channel of an ACTIVE clinic opens its own clinic
 * database.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.docker.compose.enabled=false",
      "app.provisioning.enabled=true",
      "app.provisioning.scheduler.enabled=false",
      "app.provisioning.backoff=PT0S"
    })
class WhatsappChannelIT {
  @Container static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final Path SECRETS = temporaryDirectory();

  @Autowired TenantProvisioningService provisioning;
  @Autowired TenantSecretReader secrets;
  @Autowired TenantConnectionPools pools;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    String control = POSTGRES.getDatabaseName();
    registry.add("spring.datasource.url", () -> TestPostgres.url(POSTGRES, control));
    registry.add("spring.datasource.username", () -> WORKER);
    registry.add("spring.datasource.password", () -> PASSWORD);
    registry.add("spring.flyway.url", () -> TestPostgres.url(POSTGRES, control));
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
    registry.add("app.tenancy.host", POSTGRES::getHost);
    registry.add("app.tenancy.port", () -> POSTGRES.getMappedPort(5432));
    registry.add("app.tenancy.secrets.directory", SECRETS::toString);
    registry.add("app.provisioning.admin.username", () -> PROVISIONER);
    registry.add("app.provisioning.admin.password", () -> PASSWORD);
    registry.add("app.provisioning.control-database", () -> control);
  }

  @BeforeAll
  static void deploymentRoles() throws SQLException {
    TestPostgres.bootstrapRoles(POSTGRES);
  }

  @AfterAll
  static void removeTheSecretDirectoryThisClassCreated() throws IOException {
    try (var files = Files.list(SECRETS)) {
      for (Path file : files.toList()) Files.delete(file);
    }
    Files.delete(SECRETS);
  }

  @Test
  void channelsRouteOnlyTheirOwnActiveClinic() throws Exception {
    JdbcTemplate admin = jdbc(POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate api = jdbc(RUNTIME, PASSWORD);
    JdbcTemplate worker = jdbc(WHATSAPP, PASSWORD);
    UUID clinicA = provision(admin);
    UUID clinicB = provision(admin);

    // The API inserts a PENDING channel with a sealed token but can never read it back.
    UUID pendingA = UUID.randomUUID();
    api.update(
        "INSERT INTO whatsapp_channels (id, tenant_id, status, sealed_token, key_version,"
            + " connected_by, created_at, updated_at) VALUES (?, ?, 'PENDING', ?, 1, ?, ?, ?)",
        pendingA,
        clinicA,
        new byte[] {1, 2, 3},
        UUID.randomUUID(),
        java.sql.Timestamp.from(Instant.now()),
        java.sql.Timestamp.from(Instant.now()));
    assertThatThrownBy(
            () ->
                api.queryForObject(
                    "SELECT sealed_token FROM whatsapp_channels WHERE id = ?",
                    byte[].class,
                    pendingA))
        .isInstanceOf(DataAccessException.class);
    for (String check :
        new String[] {
          "has_column_privilege('" + RUNTIME + "', 'whatsapp_channels', 'sealed_token', 'SELECT')",
          "has_column_privilege('" + RUNTIME + "', 'whatsapp_channels', 'sealed_pin', 'SELECT')",
          "has_column_privilege('" + RUNTIME + "', 'whatsapp_channels', 'status', 'UPDATE')",
          "has_table_privilege('" + RUNTIME + "', 'whatsapp_channels', 'DELETE')",
          "has_table_privilege('" + WHATSAPP + "', 'whatsapp_channels', 'DELETE')",
          "has_table_privilege('" + WHATSAPP + "', 'user_accounts', 'SELECT')",
          "has_table_privilege('" + WHATSAPP + "', 'tenant_memberships', 'SELECT')",
          "has_column_privilege('" + WHATSAPP + "', 'whatsapp_channels', 'tenant_id', 'UPDATE')",
          "has_column_privilege('" + WHATSAPP + "', 'whatsapp_channels', 'id', 'UPDATE')",
          "has_column_privilege('"
              + WHATSAPP
              + "', 'whatsapp_onboarding_tasks', 'channel_id',"
              + " 'UPDATE')",
          "has_column_privilege('"
              + RUNTIME
              + "', 'whatsapp_onboarding_tasks', 'status',"
              + " 'INSERT')",
          "has_column_privilege('" + RUNTIME + "', 'whatsapp_channels', 'waba_id', 'INSERT')"
        }) {
      assertThat(admin.queryForObject("SELECT " + check, Boolean.class)).as(check).isFalse();
    }
    // The checks above are meaningful only because the grants exist at all.
    assertThat(
            admin.queryForObject(
                "SELECT has_column_privilege('"
                    + WHATSAPP
                    + "', 'whatsapp_channels', 'status', 'UPDATE')",
                Boolean.class))
        .isTrue();
    // The API cannot create an active channel: it has no access to the asset columns.
    assertThatThrownBy(
            () ->
                api.update(
                    "INSERT INTO whatsapp_channels (id, tenant_id, status, sealed_token,"
                        + " key_version, connected_by, created_at, updated_at)"
                        + " VALUES (?, ?, 'ACTIVE', decode('01', 'hex'), 1, ?, now(), now())",
                    UUID.randomUUID(),
                    clinicB,
                    UUID.randomUUID()))
        .isInstanceOf(DataAccessException.class);

    // The worker confirms the assets and activates; one live channel per number and per clinic.
    worker.update(
        "UPDATE whatsapp_channels SET status = 'ACTIVE', waba_id = '1001',"
            + " phone_number_id = '2001' WHERE id = ?",
        pendingA);
    UUID channelB = channel(admin, clinicB, "PENDING", null);
    assertThatThrownBy(
            () ->
                admin.update(
                    "UPDATE whatsapp_channels SET status = 'ACTIVE', waba_id = '1002',"
                        + " phone_number_id = '2001' WHERE id = ?",
                    channelB))
        .isInstanceOf(DuplicateKeyException.class);
    assertThatThrownBy(() -> channel(admin, clinicA, "PENDING", null))
        .isInstanceOf(DuplicateKeyException.class);
    admin.update(
        "UPDATE whatsapp_channels SET status = 'ACTIVE', waba_id = '1002',"
            + " phone_number_id = '2002' WHERE id = ?",
        channelB);
    assertThat(new ChannelRegistryRepository(worker).findLiveChannel("2001")).contains(pendingA);
    assertThat(new ChannelRegistryRepository(api).findLiveChannel("9999")).isEmpty();
    assertThat(new ChannelRegistryRepository(api).findLiveChannel("20 01")).isEmpty();

    // Both the API (webhook) and the worker roles open only the channel's own clinic.
    for (JdbcTemplate role : new JdbcTemplate[] {api, worker}) {
      TenantRegistryRepository registry = new TenantRegistryRepository(role);
      TenantConnectionRepository connections =
          new TenantConnectionRepository(registry, secrets, pools);
      ChannelAccess access = registry.findChannelAccess(pendingA).orElseThrow();
      assertThat(access.tenantId()).isEqualTo(clinicA);
      try (Connection connection = connections.openForChannel(clinicA, pendingA)) {
        assertThat(identity(connection)).isEqualTo(clinicA);
      }
      assertThatThrownBy(() -> connections.openForChannel(clinicB, pendingA))
          .isInstanceOf(TenantAccessException.class);
      assertThatThrownBy(() -> connections.openForChannel(clinicA, UUID.randomUUID()))
          .isInstanceOf(TenantAccessException.class);
    }

    // Suspended clinic, failed or disconnected channel: no access at all.
    TenantRegistryRepository registry = new TenantRegistryRepository(worker);
    admin.update("UPDATE tenants SET status = 'SUSPENDED' WHERE tenant_id = ?", clinicB);
    assertThat(registry.findChannelAccess(channelB)).isEmpty();
    admin.update(
        "UPDATE whatsapp_channels SET status = 'FAILED', failure_code = 'TOKEN_REVOKED'"
            + " WHERE id = ?",
        pendingA);
    assertThat(registry.findChannelAccess(pendingA)).isEmpty();
    admin.update(
        "UPDATE whatsapp_channels SET status = 'DISCONNECTED', failure_code = NULL,"
            + " sealed_token = NULL, sealed_pin = NULL, disconnected_at = now() WHERE id = ?",
        pendingA);
    assertThat(registry.findChannelAccess(pendingA)).isEmpty();
    assertThat(new ChannelRegistryRepository(worker).findLiveChannel("2001")).isEmpty();
    // After disconnection the clinic can connect again.
    assertThat(channel(admin, clinicA, "PENDING", null)).isNotNull();
  }

  private static UUID channel(JdbcTemplate admin, UUID tenant, String status, String phone) {
    UUID id = UUID.randomUUID();
    admin.update(
        "INSERT INTO whatsapp_channels (id, tenant_id, status, phone_number_id, sealed_token,"
            + " key_version, connected_by, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, decode('01', 'hex'), 1, ?, now(), now())",
        id,
        tenant,
        status,
        phone,
        UUID.randomUUID());
    return id;
  }

  private UUID provision(JdbcTemplate admin) {
    UUID owner = UUID.randomUUID();
    admin.update(
        "INSERT INTO user_accounts (subject_id, email, display_name, password_hash, email_verified)"
            + " VALUES (?, ?, 'Synthetic', '{bcrypt}synthetic', true)",
        owner,
        owner + "@example.test");
    UUID tenant = UUID.randomUUID();
    String suffix = tenant.toString().replace("-", "");
    admin.update(
        "INSERT INTO tenants (tenant_id, clinic_name, database_name, database_user,"
            + " credential_reference) VALUES (?, 'Clínica sintética', ?, ?, ?)",
        tenant,
        "clinic_" + suffix,
        "tenant_" + suffix,
        "TENANT_SECRET_" + suffix.toUpperCase(Locale.ROOT));
    admin.update(
        "INSERT INTO tenant_provisioning_tasks (tenant_id, admin_subject_id, max_attempts)"
            + " VALUES (?, ?, 5)",
        tenant,
        owner);
    assertThat(provisioning.runPending()).isEqualTo(1);
    return tenant;
  }

  private static UUID identity(Connection connection) throws SQLException {
    try (var rows =
        connection.createStatement().executeQuery("SELECT tenant_id FROM tenant_identity")) {
      rows.next();
      return rows.getObject(1, UUID.class);
    }
  }

  private static JdbcTemplate jdbc(String user, String password) {
    return new JdbcTemplate(
        new DriverManagerDataSource(
            TestPostgres.url(POSTGRES, POSTGRES.getDatabaseName()), user, password));
  }

  private static Path temporaryDirectory() {
    try {
      return Files.createTempDirectory("branly-whatsapp-channel-");
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
