package br.com.branlycrm.pipeline;

import static br.com.branlycrm.support.TestPostgres.PASSWORD;
import static br.com.branlycrm.support.TestPostgres.PROVISIONER;
import static br.com.branlycrm.support.TestPostgres.WORKER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import br.com.branlycrm.pipeline.domain.LossReason;
import br.com.branlycrm.pipeline.domain.Pipeline;
import br.com.branlycrm.pipeline.domain.PipelineStage;
import br.com.branlycrm.pipeline.domain.StageKey;
import br.com.branlycrm.pipeline.domain.StageKind;
import br.com.branlycrm.pipeline.repositories.PipelineRepository;
import br.com.branlycrm.provisioning.repositories.TenantInfrastructureRepository;
import br.com.branlycrm.provisioning.services.TenantProvisioningService;
import br.com.branlycrm.support.TestPostgres;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.domain.ClinicRole;
import br.com.branlycrm.tenant.domain.TenantDatabase;
import br.com.branlycrm.tenant.exceptions.ClinicDataException;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.secrets.FileTenantSecretReader;
import br.com.branlycrm.tenant.services.TenantAccessService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Funnel, stages and loss reasons (CRM-030, ADR-033) in clinic databases created by the real
 * provisioner, read as the clinic runtime role. The funnel rules are checked in the database
 * itself, even for the clinic owner.
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
class PipelinePersistenceIT {
  @Container static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final Path SECRETS = temporaryDirectory();

  @Autowired TenantProvisioningService provisioning;
  @Autowired TenantAccessService access;
  @Autowired PipelineRepository pipelines;

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
  void everyNewClinicGetsTheDefaultFunnelAndReasonsWithItsOwnIds() throws Exception {
    Clinic a = provision();
    Clinic b = provision();

    Pipeline funnel = pipelines.get(a.access());

    assertThat(funnel.stages())
        .extracting(
            PipelineStage::name, PipelineStage::kind, PipelineStage::key, PipelineStage::position)
        .containsExactly(
            tuple("Novo", StageKind.OPEN, StageKey.NEW, 1),
            tuple("Em atendimento", StageKind.OPEN, StageKey.IN_SERVICE, 2),
            tuple("Agendado", StageKind.OPEN, StageKey.SCHEDULED, 3),
            tuple("Compareceu", StageKind.OPEN, StageKey.ATTENDED, 4),
            tuple("Venda fechada", StageKind.WON, StageKey.WON, null),
            tuple("Perdido", StageKind.LOST, StageKey.LOST, null));
    assertThat(pipelines.lossReasons(a.access(), false))
        .extracting(LossReason::name)
        .containsExactly(
            "Contato inválido",
            "Desistiu",
            "Escolheu outra clínica",
            "Outro",
            "Preço",
            "Sem horário disponível",
            "Sem resposta");

    // Random ids: an isolation test reading B's id from A cannot pass by reading its own row.
    Pipeline other = pipelines.get(b.access());
    assertThat(other.id()).isNotEqualTo(funnel.id());
    assertThat(other.stages())
        .extracting(PipelineStage::id)
        .doesNotContainAnyElementsOf(ids(funnel));
  }

  @Test
  void eachClinicReadsOnlyItsOwnFunnelAndReasons() throws Exception {
    Clinic a = provision();
    Clinic b = provision();
    owner(b, "UPDATE pipeline_stages SET name = 'Só em B' WHERE system_key = 'IN_SERVICE'");
    owner(
        b,
        "UPDATE loss_reasons SET archived_at = now(), archived_by = gen_random_uuid()"
            + " WHERE name = 'Preço'");

    assertThat(pipelines.get(a.access()).stages())
        .extracting(PipelineStage::name)
        .contains("Em atendimento")
        .doesNotContain("Só em B");
    assertThat(pipelines.lossReasons(a.access(), false))
        .extracting(LossReason::name)
        .contains("Preço");
    assertThat(pipelines.lossReasons(b.access(), false))
        .extracting(LossReason::name)
        .doesNotContain("Preço");
    assertThat(pipelines.lossReasons(b.access(), true))
        .filteredOn(LossReason::archived)
        .extracting(LossReason::name)
        .containsExactly("Preço");

    var forged = new ClinicAccess(b.database(), a.access().subjectId(), ClinicRole.OWNER);
    assertThatThrownBy(() -> pipelines.get(forged)).isInstanceOf(TenantAccessException.class);
  }

  /** The invariants hold in the database, even for the clinic owner writing SQL directly. */
  @Test
  void databaseKeepsTheFunnelRules() throws Exception {
    Clinic a = provision();
    String funnel = "(SELECT id FROM pipelines)";
    for (String[] violation :
        List.of(
            new String[] {
              "a second won stage",
              "INSERT INTO pipeline_stages (id, pipeline_id, name, kind, created_at, created_by,"
                  + " updated_at) VALUES (gen_random_uuid(), "
                  + funnel
                  + ", 'Outra venda', 'WON', now(), gen_random_uuid(), now())",
              "23505"
            },
            new String[] {
              "a second lost stage",
              "INSERT INTO pipeline_stages (id, pipeline_id, name, kind, created_at, created_by,"
                  + " updated_at) VALUES (gen_random_uuid(), "
                  + funnel
                  + ", 'Outra perda', 'LOST', now(), gen_random_uuid(), now())",
              "23505"
            },
            new String[] {
              "archiving the won stage",
              "UPDATE pipeline_stages SET archived_at = now(), archived_by = gen_random_uuid()"
                  + " WHERE kind = 'WON'",
              "23514"
            },
            new String[] {
              "archiving the entry stage",
              "UPDATE pipeline_stages SET archived_at = now(), archived_by = gen_random_uuid(),"
                  + " position = NULL WHERE system_key = 'NEW'",
              "23514"
            },
            new String[] {
              "a position on the lost stage",
              "UPDATE pipeline_stages SET position = 9 WHERE kind = 'LOST'",
              "23514"
            },
            new String[] {
              "an open stage without position",
              "UPDATE pipeline_stages SET position = NULL WHERE system_key = 'SCHEDULED'",
              "23514"
            },
            new String[] {
              "two stages in one position",
              "UPDATE pipeline_stages SET position = 1 WHERE system_key = 'IN_SERVICE'",
              "23505"
            },
            new String[] {
              "a key of another kind",
              "UPDATE pipeline_stages SET kind = 'OPEN', position = 9 WHERE system_key = 'WON'",
              "23514"
            },
            new String[] {
              "a stage without author nor key",
              "INSERT INTO pipeline_stages (id, pipeline_id, name, kind, position, created_at,"
                  + " updated_at) VALUES (gen_random_uuid(), "
                  + funnel
                  + ", 'Sem autor', 'OPEN', 5, now(), now())",
              "23514"
            },
            new String[] {
              "archiving the lost stage",
              "UPDATE pipeline_stages SET archived_at = now(), archived_by = gen_random_uuid()"
                  + " WHERE kind = 'LOST'",
              "23514"
            },
            new String[] {
              "two active stages with the same name in another case",
              "UPDATE pipeline_stages SET name = 'novo' WHERE system_key = 'IN_SERVICE'",
              "23505"
            },
            new String[] {
              "two stages with the same key",
              "UPDATE pipeline_stages SET system_key = 'SCHEDULED' WHERE system_key = 'ATTENDED'",
              "23505"
            },
            new String[] {
              "an archived stage without who archived it",
              "UPDATE pipeline_stages SET archived_at = now(), position = NULL"
                  + " WHERE system_key = 'ATTENDED'",
              "23514"
            },
            new String[] {
              "a blank stage name",
              "UPDATE pipeline_stages SET name = '   ' WHERE system_key = 'ATTENDED'",
              "23514"
            },
            new String[] {
              "an archived reason without who archived it",
              "UPDATE loss_reasons SET archived_at = now() WHERE name = 'Outro'",
              "23514"
            },
            new String[] {
              "a reason without author that is not initial",
              "INSERT INTO loss_reasons (id, name, created_at, updated_at)"
                  + " VALUES (gen_random_uuid(), 'Sem autor', now(), now())",
              "23514"
            },
            new String[] {
              "a reason with the same name in another case",
              "INSERT INTO loss_reasons (id, name, created_at, created_by, updated_at)"
                  + " VALUES (gen_random_uuid(), 'preço', now(), gen_random_uuid(), now())",
              "23505"
            })) {
      assertThat(sqlState(a, violation[1])).as(violation[0]).isEqualTo(violation[2]);
    }

    // Positions are unique at the end of each statement, so one UPDATE swaps two stages.
    owner(
        a,
        "UPDATE pipeline_stages SET position = CASE system_key WHEN 'SCHEDULED' THEN 4"
            + " ELSE 3 END WHERE system_key IN ('SCHEDULED', 'ATTENDED')");
    assertThat(pipelines.get(a.access()).stages())
        .extracting(PipelineStage::key)
        .startsWith(StageKey.NEW, StageKey.IN_SERVICE, StageKey.ATTENDED, StageKey.SCHEDULED);
  }

  @Test
  void runtimeConfiguresTheFunnelOnlyWithinItsGrants() throws Exception {
    Clinic a = provision();
    // V11 (CRM-036): names, positions, archiving and versions; never kind, key, funnel nor
    // creation authorship, never deletes, and only one funnel.
    try (Connection runtime = runtime(a.database())) {
      for (String forbidden :
          List.of(
              "INSERT INTO pipelines (id, name, created_at, updated_at)"
                  + " VALUES (gen_random_uuid(), 'Outro funil', now(), now())",
              "DELETE FROM pipelines",
              "UPDATE pipelines SET name = 'X'",
              "UPDATE pipeline_stages SET kind = 'OPEN'",
              "UPDATE pipeline_stages SET system_key = NULL",
              "UPDATE pipeline_stages SET pipeline_id = gen_random_uuid()",
              "UPDATE pipeline_stages SET created_by = gen_random_uuid()",
              "DELETE FROM pipeline_stages",
              "UPDATE loss_reasons SET seeded = false",
              "UPDATE loss_reasons SET created_by = gen_random_uuid()",
              "DELETE FROM loss_reasons",
              "TRUNCATE loss_reasons")) {
        assertThatThrownBy(() -> runtime.createStatement().execute(forbidden))
            .as(forbidden)
            .isInstanceOf(SQLException.class)
            .extracting(error -> ((SQLException) error).getSQLState())
            .isEqualTo("42501");
      }
    }
    String role = a.database().databaseUser();
    for (String table : List.of("pipelines", "pipeline_stages", "loss_reasons")) {
      try (Connection owner = asOwner(a);
          var rows =
              owner
                  .createStatement()
                  .executeQuery(
                      "SELECT has_table_privilege('"
                          + role
                          + "', '"
                          + table
                          + "', 'SELECT'), has_table_privilege('"
                          + role
                          + "', '"
                          + table
                          + "', 'UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')")) {
        rows.next();
        assertThat(rows.getBoolean(1)).as(table + " SELECT").isTrue();
        assertThat(rows.getBoolean(2)).as(table + " table-wide writes").isFalse();
      }
    }
  }

  /** A funnel changed outside the rules is invalid data, never shown in a wrong order. */
  @Test
  void funnelChangedOutsideTheRulesIsInvalidData() throws Exception {
    Clinic gap = provision();
    owner(
        gap,
        "UPDATE pipeline_stages SET archived_at = now(), archived_by = gen_random_uuid(),"
            + " position = NULL WHERE system_key = 'IN_SERVICE'");
    assertInvalid(gap);

    Clinic twoFunnels = provision();
    owner(
        twoFunnels,
        "INSERT INTO pipelines (id, name, created_at, updated_at)"
            + " VALUES (gen_random_uuid(), 'Segundo funil', now(), now())");
    assertInvalid(twoFunnels);
  }

  private void assertInvalid(Clinic clinic) {
    assertThatThrownBy(() -> pipelines.get(clinic.access()))
        .isInstanceOf(ClinicDataException.class)
        .hasMessage("CLINIC_DATA_INVALID_DATA");
  }

  private record Clinic(TenantDatabase database, ClinicAccess access) {}

  private static List<UUID> ids(Pipeline pipeline) {
    List<UUID> ids = new ArrayList<>();
    pipeline.stages().forEach(stage -> ids.add(stage.id()));
    return ids;
  }

  private Clinic provision() {
    UUID owner = UUID.randomUUID();
    superuser.update(
        "INSERT INTO user_accounts (subject_id, email, display_name, password_hash, email_verified)"
            + " VALUES (?, ?, 'Synthetic', '{bcrypt}synthetic', true)",
        owner,
        owner + "@example.test");
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
    return new Clinic(database, access.authorize(tenant, owner));
  }

  /** Runs SQL as the clinic owner role, the one that applies the migrations. */
  private static void owner(Clinic clinic, String sql) throws SQLException {
    try (Connection owner = asOwner(clinic)) {
      owner.createStatement().execute(sql);
    }
  }

  private static String sqlState(Clinic clinic, String sql) {
    try (Connection owner = asOwner(clinic)) {
      owner.createStatement().execute(sql);
      return null;
    } catch (SQLException refused) {
      return refused.getSQLState();
    }
  }

  private static Connection asOwner(Clinic clinic) throws SQLException {
    Connection connection = TestPostgres.admin(POSTGRES, clinic.database().databaseName());
    connection
        .createStatement()
        .execute(
            "SET ROLE \"" + TenantInfrastructureRepository.ownerRole(clinic.database()) + "\"");
    return connection;
  }

  private Connection runtime(TenantDatabase database) throws SQLException {
    return DriverManager.getConnection(
        TestPostgres.url(POSTGRES, database.databaseName()),
        database.databaseUser(),
        new FileTenantSecretReader(SECRETS.toString()).read(database).orElseThrow().password());
  }

  private static Path temporaryDirectory() {
    try {
      return Files.createTempDirectory("branly-pipeline-persistence-");
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
