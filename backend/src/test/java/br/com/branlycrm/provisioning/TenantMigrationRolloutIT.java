package br.com.branlycrm.provisioning;

import static br.com.branlycrm.support.TestPostgres.PASSWORD;
import static br.com.branlycrm.support.TestPostgres.PROVISIONER;
import static br.com.branlycrm.support.TestPostgres.WORKER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.common.dto.response.PageView;
import br.com.branlycrm.contact.dto.request.ContactRequest;
import br.com.branlycrm.contact.dto.response.ContactSummaryView;
import br.com.branlycrm.contact.services.ContactService;
import br.com.branlycrm.pipeline.repositories.PipelineRepository;
import br.com.branlycrm.provisioning.repositories.TenantInfrastructureRepository;
import br.com.branlycrm.provisioning.repositories.TenantRolloutRepository;
import br.com.branlycrm.provisioning.repositories.TenantRuntimeProbeRepository;
import br.com.branlycrm.provisioning.services.TenantMigrationRollout;
import br.com.branlycrm.provisioning.services.TenantProvisioningService;
import br.com.branlycrm.support.TestPostgres;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.domain.ClinicSettings;
import br.com.branlycrm.tenant.domain.TenantDatabase;
import br.com.branlycrm.tenant.exceptions.ClinicDataException;
import br.com.branlycrm.tenant.repositories.ClinicSettingsRepository;
import br.com.branlycrm.tenant.secrets.TenantSecretReader;
import br.com.branlycrm.tenant.services.TenantAccessService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Schema rollout for existing clinics (ADR-031) against clinics created by the real provisioner,
 * with deployment roles without superuser. A clinic created before CRM-015 is simulated by
 * removing, as its owner, what V3 (clinic settings), V4 (contacts) and V5 (funnel) created and
 * their history rows: the rollout must bring them back, and the clinic routes work again.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.docker.compose.enabled=false",
      "app.provisioning.enabled=true",
      "app.provisioning.scheduler.enabled=false",
      "app.provisioning.backoff=PT0S"
    })
class TenantMigrationRolloutIT {
  @Container static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final Path SECRETS = temporaryDirectory();

  /** Migrations after V2 (V3 clinic settings, V4 contacts, and any newer one). */
  private static final int PENDING = migrationsOnClasspath() - 2;

  @Autowired TenantProvisioningService provisioning;
  @Autowired TenantAccessService access;
  @Autowired ClinicSettingsRepository settings;
  @Autowired ContactService contacts;
  @Autowired PipelineRepository pipelines;
  @Autowired TenantSecretReader secrets;
  @Autowired JdbcTemplate worker;

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
  void clinicWithoutTheNewestMigrationsIsMigratedAndWorksAgain() throws Exception {
    Clinic a = provision();
    downgradeToV2(a.database());
    assertThatThrownBy(() -> settings.find(a.access()))
        .isInstanceOf(ClinicDataException.class)
        .hasMessage("CLINIC_DATA_SCHEMA_OUTDATED");
    // What the contact routes answer with 503 today.
    assertThatThrownBy(() -> listContacts(a))
        .isInstanceOf(ClinicDataException.class)
        .hasMessage("CLINIC_DATA_SCHEMA_OUTDATED");

    Run first = rollout("--tenant-id", a.tenantId().toString());

    assertThat(first.exitCode()).isZero();
    assertThat(first.output())
        .contains(
            "tenantId="
                + a.tenantId()
                + " result=MIGRATED stage=VERIFICATION applied="
                + PENDING
                + "\n")
        .contains("summary: clinics=1 migrated=1 upToDate=0 failed=0");
    assertThat(historyRows(a.database())).isEqualTo(migrationsOnClasspath());
    // V3: clinic settings, read and written by the runtime.
    assertThat(settings.find(a.access()).timeZone()).isEqualTo(ClinicSettings.DEFAULT_TIME_ZONE);
    ClinicSettings manaus = ClinicSettings.of("America/Manaus");
    assertThat(settings.changeTimeZone(a.access(), manaus)).isEqualTo(manaus);
    // V4: the same service calls as the contact routes, with the runtime grants of the migration.
    var created =
        contacts.create(
            a.tenantId(),
            a.owner(),
            new ContactRequest("Contato Sintético", "(11) 98765-4321", null, null, null, null));
    assertThat(contacts.get(a.tenantId(), a.owner(), created.id()).name())
        .isEqualTo("Contato Sintético");
    assertThat(listContacts(a).total()).isEqualTo(1);
    // V5: the default funnel and reasons, read as the runtime with the migration's grants.
    assertThat(pipelines.get(a.access()).stages()).hasSize(6);
    assertThat(pipelines.lossReasons(a.access(), false)).hasSize(7);

    Run again = rollout("--tenant-id", a.tenantId().toString());

    assertThat(again.exitCode()).isZero();
    assertThat(again.output())
        .contains("tenantId=" + a.tenantId() + " result=UP_TO_DATE stage=VERIFICATION applied=0");
    assertThat(historyRows(a.database())).isEqualTo(migrationsOnClasspath());
    assertThat(settings.find(a.access()).timeZone()).isEqualTo(manaus.timeZone());
    assertThat(listContacts(a).total()).isEqualTo(1);
  }

  /** B fails, A is still migrated; a second run after fixing B resumes it. */
  @Test
  void failureOfOneClinicIsIsolatedAndResumable(CapturedOutput logs) throws Exception {
    Clinic a = provision();
    Clinic b = provision();
    downgradeToV2(a.database());
    // History rows gone but objects kept: V3 runs again and fails on CREATE TABLE.
    forgetNewerHistory(b.database());

    Run run = rollout();

    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.output())
        .contains(
            "tenantId="
                + a.tenantId()
                + " result=MIGRATED stage=VERIFICATION applied="
                + PENDING
                + "\n")
        .contains(
            "tenantId="
                + b.tenantId()
                + " result=FAILED stage=MIGRATION applied=0 code=MIGRATION_FAILED\n")
        .contains("failed=1")
        .doesNotContain("already exists")
        .doesNotContain("clinic_settings")
        .doesNotContain("ERROR")
        .doesNotContain("Exception");
    assertThat(settings.find(a.access()).timeZone()).isEqualTo(ClinicSettings.DEFAULT_TIME_ZONE);
    // Neither the logs (Flyway, Spring, this command) carry the PostgreSQL error text.
    assertThat(logs.getAll())
        .contains("schema rollout failed at MIGRATION with MIGRATION_FAILED")
        .doesNotContain("relation \"clinic_settings\" already exists");

    dropNewerObjects(b.database());
    Run resumed = rollout();

    assertThat(resumed.exitCode()).isZero();
    assertThat(resumed.output())
        .contains("tenantId=" + a.tenantId() + " result=UP_TO_DATE")
        .contains(
            "tenantId=" + b.tenantId() + " result=MIGRATED stage=VERIFICATION applied=" + PENDING);
    assertThat(settings.find(b.access()).timeZone()).isEqualTo(ClinicSettings.DEFAULT_TIME_ZONE);
    assertThat(listContacts(b).total()).isZero();
  }

  /**
   * SUSPENDED is reached (reactivation must not find an old schema); PROVISIONING and FAILED stay
   * with the queue; an eligible clinic without database fails without anything being created.
   */
  @Test
  void selectionCoversHandedOverClinicsOnlyAndNeverCreatesADatabase() throws Exception {
    Clinic suspended = provision();
    downgradeToV2(suspended.database());
    superuser.update(
        "UPDATE tenants SET status = 'SUSPENDED' WHERE tenant_id = ?", suspended.tenantId());
    TenantDatabase provisioningRow = registryOnly("PROVISIONING");
    TenantDatabase failedRow = registryOnly("FAILED");
    TenantDatabase missing = registryOnly("SUSPENDED");
    try {
      Run run = rollout();

      assertThat(run.exitCode()).isEqualTo(1);
      assertThat(run.output())
          .contains("tenantId=" + suspended.tenantId() + " result=MIGRATED")
          .contains(
              "tenantId="
                  + missing.tenantId()
                  + " result=FAILED stage=PRECHECK applied=0 code=DATABASE_MISSING")
          .doesNotContain(provisioningRow.tenantId().toString())
          .doesNotContain(failedRow.tenantId().toString());
      assertThat(
              superuser.queryForObject(
                  "SELECT count(*) FROM pg_database WHERE datname = ?",
                  Integer.class,
                  missing.databaseName()))
          .isZero();
      assertThat(historyRows(suspended.database())).isEqualTo(migrationsOnClasspath());

      Run notEligible = rollout("--tenant-id", provisioningRow.tenantId().toString());
      assertThat(notEligible.exitCode()).isEqualTo(1);
      assertThat(notEligible.output())
          .contains("stage=SELECTION applied=0 code=TENANT_NOT_ELIGIBLE");
    } finally {
      superuser.update(
          "DELETE FROM tenants WHERE tenant_id IN (?, ?, ?)",
          provisioningRow.tenantId(),
          failedRow.tenantId(),
          missing.tenantId());
      superuser.update(
          "UPDATE tenants SET status = 'ACTIVE' WHERE tenant_id = ?", suspended.tenantId());
    }
  }

  @Test
  void onlyTheRequestedClinicIsTouched() throws Exception {
    Clinic a = provision();
    Clinic b = provision();
    downgradeToV2(a.database());
    downgradeToV2(b.database());
    try {
      Run run = rollout("--tenant-id", a.tenantId().toString());

      assertThat(run.exitCode()).isZero();
      assertThat(run.output()).doesNotContain(b.tenantId().toString());
      assertThat(historyRows(a.database())).isEqualTo(migrationsOnClasspath());
      assertThat(historyRows(b.database())).isEqualTo(2);
    } finally {
      rollout("--tenant-id", b.tenantId().toString());
    }
  }

  /** Flyway locks each database: two runs at once apply each migration exactly once. */
  @Test
  void concurrentRunsApplyEachMigrationOnce() throws Exception {
    Clinic a = provision();
    downgradeToV2(a.database());
    CyclicBarrier start = new CyclicBarrier(2);

    List<CompletableFuture<Run>> runs =
        List.of(1, 2).stream()
            .map(
                i ->
                    CompletableFuture.supplyAsync(
                        () -> {
                          try {
                            start.await();
                          } catch (Exception interrupted) {
                            throw new IllegalStateException(interrupted);
                          }
                          return rollout("--tenant-id", a.tenantId().toString());
                        }))
            .toList();
    List<Run> results = runs.stream().map(CompletableFuture::join).toList();

    assertThat(results).allMatch(run -> run.exitCode() == 0);
    assertThat(results.stream().map(Run::output))
        .filteredOn(
            output -> output.contains("result=MIGRATED stage=VERIFICATION applied=" + PENDING))
        .hasSize(1);
    assertThat(results.stream().map(Run::output))
        .filteredOn(output -> output.contains("result=UP_TO_DATE stage=VERIFICATION applied=0"))
        .hasSize(1);
    assertThat(historyRows(a.database())).isEqualTo(migrationsOnClasspath());
  }

  private record Run(int exitCode, String output) {}

  private record Clinic(TenantDatabase database, ClinicAccess access) {
    UUID tenantId() {
      return database.tenantId();
    }

    UUID owner() {
      return access.subjectId();
    }
  }

  /** The command as {@code APP_MODE=tenant-migrate} builds it, with the deployment roles. */
  private Run rollout(String... args) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    var command =
        new TenantMigrationRollout(
            new TenantRolloutRepository(worker),
            new TenantInfrastructureRepository(
                POSTGRES.getHost(),
                POSTGRES.getMappedPort(5432),
                "postgres",
                PROVISIONER,
                PASSWORD),
            new TenantRuntimeProbeRepository(POSTGRES.getHost(), POSTGRES.getMappedPort(5432)),
            secrets,
            List.of(POSTGRES.getDatabaseName(), "postgres"),
            new PrintStream(output, true, StandardCharsets.UTF_8));
    command.run(args);
    return new Run(
        command.getExitCode(), output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
  }

  /** As the clinic owner, back to a clinic created with V1 and V2 only: objects and history. */
  private void downgradeToV2(TenantDatabase database) throws SQLException {
    dropNewerObjects(database);
    forgetNewerHistory(database);
  }

  /** Everything V3, V4 and V5 created. */
  private void dropNewerObjects(TenantDatabase database) throws SQLException {
    asOwner(database, "DROP TABLE loss_reasons, pipeline_stages, pipelines");
    asOwner(database, "DROP TABLE contact_consents, contact_tags, tags, contacts");
    asOwner(database, "DROP EXTENSION pg_trgm");
    asOwner(database, "DROP TABLE clinic_settings");
  }

  private void forgetNewerHistory(TenantDatabase database) throws SQLException {
    // Every row after V2. dropNewerObjects must remove the objects of each new migration.
    asOwner(
        database,
        "DELETE FROM flyway_schema_history WHERE version IS NOT NULL AND version::int > 2");
  }

  private PageView<ContactSummaryView> listContacts(Clinic clinic) {
    return contacts.list(clinic.tenantId(), clinic.owner(), null, null, null, null, null);
  }

  private void asOwner(TenantDatabase database, String sql) throws SQLException {
    try (Connection connection = TestPostgres.admin(POSTGRES, database.databaseName());
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE \"" + TenantInfrastructureRepository.ownerRole(database) + "\"");
      statement.execute(sql);
    }
  }

  private int historyRows(TenantDatabase database) throws SQLException {
    try (Connection connection = TestPostgres.admin(POSTGRES, database.databaseName());
        var rows =
            connection
                .createStatement()
                .executeQuery(
                    "SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL")) {
      rows.next();
      return rows.getInt(1);
    }
  }

  /** V1..Vn are consecutive, so n is the newest version and the number of applied rows. */
  private static int migrationsOnClasspath() {
    try {
      var resources =
          new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
              .getResources("classpath:db/tenant-migration/V*__*.sql");
      return resources.length;
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private Clinic provision() {
    UUID owner = account();
    TenantDatabase database = names(UUID.randomUUID());
    superuser.update(
        "INSERT INTO tenants (tenant_id, clinic_name, database_name, database_user,"
            + " credential_reference) VALUES (?, 'Clínica sintética', ?, ?, ?)",
        database.tenantId(),
        database.databaseName(),
        database.databaseUser(),
        database.credentialReference());
    superuser.update(
        "INSERT INTO tenant_provisioning_tasks (tenant_id, admin_subject_id, max_attempts)"
            + " VALUES (?, ?, 5)",
        database.tenantId(),
        owner);
    assertThat(provisioning.runPending()).isEqualTo(1);
    return new Clinic(database, access.authorize(database.tenantId(), owner));
  }

  /** A registry row whose database was never created. */
  private TenantDatabase registryOnly(String status) {
    TenantDatabase database = names(UUID.randomUUID());
    superuser.update(
        "INSERT INTO tenants (tenant_id, clinic_name, database_name, database_user,"
            + " credential_reference, status) VALUES (?, 'Clínica sintética', ?, ?, ?, ?)",
        database.tenantId(),
        database.databaseName(),
        database.databaseUser(),
        database.credentialReference(),
        status);
    return database;
  }

  private static TenantDatabase names(UUID tenant) {
    String suffix = tenant.toString().replace("-", "");
    return new TenantDatabase(
        tenant,
        "clinic_" + suffix,
        "tenant_" + suffix,
        "TENANT_SECRET_" + suffix.toUpperCase(Locale.ROOT));
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

  private static Path temporaryDirectory() {
    try {
      return Files.createTempDirectory("branly-tenant-rollout-");
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
