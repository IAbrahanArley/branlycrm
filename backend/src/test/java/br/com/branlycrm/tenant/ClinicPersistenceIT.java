package br.com.branlycrm.tenant;

import static br.com.branlycrm.support.TestPostgres.PASSWORD;
import static br.com.branlycrm.support.TestPostgres.PROVISIONER;
import static br.com.branlycrm.support.TestPostgres.WORKER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.provisioning.services.TenantProvisioningService;
import br.com.branlycrm.support.TestPostgres;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.domain.ClinicRole;
import br.com.branlycrm.tenant.domain.ClinicSettings;
import br.com.branlycrm.tenant.domain.TenantDatabase;
import br.com.branlycrm.tenant.exceptions.ClinicDataException;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.persistence.ClinicPersistence;
import br.com.branlycrm.tenant.persistence.ClinicSettingsEntity;
import br.com.branlycrm.tenant.persistence.ClinicTenant;
import br.com.branlycrm.tenant.repositories.ClinicSettingsRepository;
import br.com.branlycrm.tenant.repositories.TenantConnectionPools;
import br.com.branlycrm.tenant.secrets.FileTenantSecretReader;
import br.com.branlycrm.tenant.services.TenantAccessService;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Clinic persistence unit (CRM-015, ADR-030) against clinics created by the real provisioner: an
 * entity reads and writes only the database of the clinic its access authorizes, and the central
 * EntityManager knows no entity.
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
class ClinicPersistenceIT {
  @Container static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final Path SECRETS = temporaryDirectory();

  @Autowired TenantProvisioningService provisioning;
  @Autowired TenantAccessService access;
  @Autowired ClinicSettingsRepository settings;
  @Autowired ClinicPersistence persistence;
  @Autowired TenantConnectionPools pools;
  @Autowired ApplicationContext context;

  JdbcTemplate superuser;

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

  @Autowired
  void superuser() {
    superuser =
        new JdbcTemplate(
            new DriverManagerDataSource(
                TestPostgres.url(POSTGRES, POSTGRES.getDatabaseName()),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()));
  }

  @Test
  void eachClinicReadsAndWritesOnlyItsOwnDatabase() throws Exception {
    Clinic a = provision();
    Clinic b = provision();

    assertThat(settings.find(a.access()).timeZone()).isEqualTo(ClinicSettings.DEFAULT_TIME_ZONE);
    assertThat(settings.find(b.access()).timeZone()).isEqualTo(ClinicSettings.DEFAULT_TIME_ZONE);

    ClinicSettings manaus = ClinicSettings.of("America/Manaus");
    assertThat(settings.changeTimeZone(a.access(), manaus)).isEqualTo(manaus);

    assertThat(settings.find(a.access()).timeZone()).isEqualTo(ZoneId.of("America/Manaus"));
    assertThat(settings.find(b.access()).timeZone()).isEqualTo(ClinicSettings.DEFAULT_TIME_ZONE);
    assertThat(storedZone(a.database())).isEqualTo("America/Manaus");
    assertThat(storedZone(b.database())).isEqualTo("America/Sao_Paulo");
  }

  @Test
  void subjectOfOneClinicCannotReachAnotherBeforeAnyConnection() throws Exception {
    Clinic a = provision();
    Clinic b = provision();
    settings.find(a.access());
    int poolsBefore = pools.openPools();

    // A forged access pairing A's subject with B's clinic: the registry refuses it before any
    // pool or connection to B exists, for reads and for writes.
    ClinicAccess forged = new ClinicAccess(b.database(), a.owner(), ClinicRole.OWNER);
    assertThatThrownBy(() -> settings.find(forged)).isInstanceOf(TenantAccessException.class);
    assertThatThrownBy(() -> settings.changeTimeZone(forged, ClinicSettings.of("Europe/Lisbon")))
        .isInstanceOf(TenantAccessException.class);
    assertThatThrownBy(() -> access.authorize(b.tenantId(), a.owner()))
        .isInstanceOf(TenantAccessException.class);
    assertThat(pools.openPools()).as("no pool opened for B").isEqualTo(poolsBefore);
    assertThat(storedZone(b.database())).isEqualTo("America/Sao_Paulo");
  }

  @Test
  void deactivatedMembershipLosesAccessEvenWithAnAccessBuiltBefore() throws Exception {
    Clinic a = provision();
    UUID attendant = account();
    superuser.update(
        "INSERT INTO tenant_memberships (tenant_id, subject_id, role) VALUES (?, ?, 'ATTENDANT')",
        a.tenantId(),
        attendant);
    ClinicAccess stale = access.authorize(a.tenantId(), attendant);
    assertThat(settings.find(stale).timeZone()).isEqualTo(ClinicSettings.DEFAULT_TIME_ZONE);

    superuser.update(
        "UPDATE tenant_memberships SET active = false WHERE tenant_id = ? AND subject_id = ?",
        a.tenantId(),
        attendant);

    assertThatThrownBy(() -> settings.find(stale)).isInstanceOf(TenantAccessException.class);
  }

  @Test
  void failedCallbackRollsBackAndKeepsItsException() throws Exception {
    Clinic a = provision();
    IllegalStateException failure = new IllegalStateException("synthetic failure");

    assertThatThrownBy(
            () ->
                persistence.inTransaction(
                    ClinicTenant.of(a.access()),
                    session -> {
                      session
                          .find(ClinicSettingsEntity.class, ClinicSettingsEntity.KEY)
                          .changeTimeZone("Europe/Lisbon");
                      session.flush();
                      throw failure;
                    }))
        .isSameAs(failure);

    assertThat(storedZone(a.database())).isEqualTo("America/Sao_Paulo");
  }

  @Test
  void runtimeChangesOnlyTheTimeZoneColumn() throws Exception {
    Clinic a = provision();
    try (Connection runtime = runtime(a.database())) {
      for (String forbidden :
          List.of(
              "INSERT INTO clinic_settings (singleton) VALUES (true)",
              "DELETE FROM clinic_settings",
              "UPDATE clinic_settings SET singleton = true",
              "TRUNCATE clinic_settings")) {
        assertThatThrownBy(() -> runtime.createStatement().execute(forbidden))
            .as(forbidden)
            .isInstanceOf(SQLException.class)
            .extracting(error -> ((SQLException) error).getSQLState())
            .isEqualTo("42501");
      }
      assertThatThrownBy(
              () ->
                  runtime
                      .createStatement()
                      .execute("UPDATE clinic_settings SET time_zone = '+03:00'"))
          .as("check constraint keeps offsets out")
          .extracting(error -> ((SQLException) error).getSQLState())
          .isEqualTo("23514");
    }
  }

  @Test
  void outdatedClinicDatabaseFailsClosedWithAStableCode() throws Exception {
    Clinic a = provision();
    try (Connection owner = TestPostgres.admin(POSTGRES, a.database().databaseName())) {
      owner.createStatement().execute("DROP TABLE clinic_settings");
    }

    assertThatThrownBy(() -> settings.find(a.access()))
        .isInstanceOf(ClinicDataException.class)
        .hasMessage("CLINIC_DATA_SCHEMA_OUTDATED")
        .extracting(error -> ((ClinicDataException) error).reason())
        .isEqualTo(ClinicDataException.Reason.SCHEMA_OUTDATED);
  }

  @Test
  void nestedTransactionFailsAtOnceInsteadOfWaitingOnThePool() throws Exception {
    Clinic a = provision();
    ClinicTenant tenant = ClinicTenant.of(a.access());
    long started = System.nanoTime();

    assertThatThrownBy(
            () ->
                persistence.inTransaction(
                    tenant, outer -> persistence.inTransaction(tenant, inner -> null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Clinic transactions must not be nested");

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
    assertThat(settings.find(a.access()).timeZone()).isEqualTo(ClinicSettings.DEFAULT_TIME_ZONE);
    assertThatThrownBy(() -> persistence.inTransaction(null, session -> null))
        .isInstanceOf(NullPointerException.class);
  }

  /** A writer blocked by a lock gives its connection back within the lock timeout. */
  @Test
  void blockedWriteGivesUpWithinTheLockTimeout() throws Exception {
    Clinic a = provision();
    try (Connection owner = TestPostgres.admin(POSTGRES, a.database().databaseName())) {
      owner.setAutoCommit(false);
      owner.createStatement().execute("SELECT * FROM clinic_settings FOR UPDATE");
      long started = System.nanoTime();

      assertThatThrownBy(
              () -> settings.changeTimeZone(a.access(), ClinicSettings.of("Europe/Lisbon")))
          .isInstanceOf(ClinicDataException.class)
          .extracting(error -> ((ClinicDataException) error).reason())
          .isEqualTo(ClinicDataException.Reason.UNAVAILABLE);

      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
      owner.rollback();
    }
    assertThat(storedZone(a.database())).isEqualTo("America/Sao_Paulo");
  }

  @Test
  void valueWrittenOutsideTheApplicationIsReportedAsInvalidData() throws Exception {
    Clinic a = provision();
    try (Connection owner = TestPostgres.admin(POSTGRES, a.database().databaseName())) {
      // Passes the database check, but the JDK knows no such region.
      owner.createStatement().execute("UPDATE clinic_settings SET time_zone = 'America/Atlantis'");
    }

    assertThatThrownBy(() -> settings.find(a.access()))
        .isInstanceOf(ClinicDataException.class)
        .hasMessage("CLINIC_DATA_INVALID_DATA");
  }

  @Test
  void databaseDeclaringAnotherClinicIsAnIdentityIncident() throws Exception {
    Clinic a = provision();
    try (Connection owner = TestPostgres.admin(POSTGRES, a.database().databaseName())) {
      owner.createStatement().execute("UPDATE tenant_identity SET tenant_id = gen_random_uuid()");
    }

    assertThatThrownBy(() -> settings.find(a.access()))
        .isInstanceOf(ClinicDataException.class)
        .extracting(error -> ((ClinicDataException) error).reason())
        .isEqualTo(ClinicDataException.Reason.IDENTITY_MISMATCH);
  }

  @Test
  void centralEntityManagerManagesNoEntity() {
    assertThat(context.getBeansOfType(EntityManagerFactory.class)).hasSize(1);
    EntityManagerFactory central = context.getBean(EntityManagerFactory.class);
    assertThat(central.getMetamodel().getEntities()).isEmpty();
    assertThat(persistence.entities()).contains(ClinicSettingsEntity.class);
  }

  /** Hibernate validates the clinic mappings against a provisioned database, as the runtime. */
  @Test
  void clinicMappingsMatchTheProvisionedSchema() throws Exception {
    Clinic a = provision();
    Map<String, Object> validation = new HashMap<>(ClinicPersistence.settings());
    validation.put("hibernate.boot.allow_jdbc_metadata_access", true);
    validation.put("hibernate.hbm2ddl.auto", "validate");
    validation.put(
        "hibernate.connection.url", TestPostgres.url(POSTGRES, a.database().databaseName()));
    validation.put("hibernate.connection.username", a.database().databaseUser());
    validation.put("hibernate.connection.password", password(a.database()));
    StandardServiceRegistry registry =
        new StandardServiceRegistryBuilder().applySettings(validation).build();
    try {
      MetadataSources sources = new MetadataSources(registry);
      persistence.entities().forEach(sources::addAnnotatedClass);
      sources.buildMetadata().buildSessionFactory().close();
    } finally {
      StandardServiceRegistryBuilder.destroy(registry);
    }
  }

  private record Clinic(TenantDatabase database, UUID owner, ClinicAccess access) {
    UUID tenantId() {
      return database.tenantId();
    }
  }

  private Clinic provision() {
    UUID owner = account();
    UUID tenant = UUID.randomUUID();
    String suffix = tenant.toString().replace("-", "");
    var database =
        new TenantDatabase(
            tenant,
            "clinic_" + suffix,
            "tenant_" + suffix,
            "TENANT_SECRET_" + suffix.toUpperCase(Locale.ROOT));
    superuser.update(
        "INSERT INTO tenants (tenant_id, clinic_name, database_name, database_user,"
            + " credential_reference) VALUES (?, 'Clínica sintética', ?, ?, ?)",
        tenant,
        database.databaseName(),
        database.databaseUser(),
        database.credentialReference());
    superuser.update(
        "INSERT INTO tenant_provisioning_tasks (tenant_id, admin_subject_id, max_attempts)"
            + " VALUES (?, ?, 5)",
        tenant,
        owner);
    assertThat(provisioning.runPending()).isEqualTo(1);
    return new Clinic(database, owner, access.authorize(tenant, owner));
  }

  private UUID account() {
    UUID id = UUID.randomUUID();
    superuser.update(
        "INSERT INTO user_accounts (subject_id, email, display_name, password_hash, email_verified)"
            + " VALUES (?, ?, 'Synthetic', '{bcrypt}synthetic', true)",
        id,
        id + "@example.test");
    return id;
  }

  private String storedZone(TenantDatabase database) throws SQLException {
    try (Connection owner = TestPostgres.admin(POSTGRES, database.databaseName());
        var rows = owner.createStatement().executeQuery("SELECT time_zone FROM clinic_settings")) {
      assertThat(rows.next()).isTrue();
      return rows.getString(1);
    }
  }

  private Connection runtime(TenantDatabase database) throws SQLException {
    return DriverManager.getConnection(
        TestPostgres.url(POSTGRES, database.databaseName()),
        database.databaseUser(),
        password(database));
  }

  private static String password(TenantDatabase database) {
    return new FileTenantSecretReader(SECRETS.toString()).read(database).orElseThrow().password();
  }

  private static Path temporaryDirectory() {
    try {
      return Files.createTempDirectory("branly-clinic-persistence-");
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
