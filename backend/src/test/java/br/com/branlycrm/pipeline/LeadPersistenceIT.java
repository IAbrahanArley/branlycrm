package br.com.branlycrm.pipeline;

import static br.com.branlycrm.support.TestPostgres.PASSWORD;
import static br.com.branlycrm.support.TestPostgres.PROVISIONER;
import static br.com.branlycrm.support.TestPostgres.WORKER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.contact.domain.ContactCreatedVia;
import br.com.branlycrm.contact.domain.ContactFields;
import br.com.branlycrm.contact.domain.PhoneNumber;
import br.com.branlycrm.contact.repositories.ContactRepository;
import br.com.branlycrm.pipeline.domain.LossReason;
import br.com.branlycrm.pipeline.domain.PipelineStage;
import br.com.branlycrm.pipeline.domain.StageKey;
import br.com.branlycrm.pipeline.domain.Temperature;
import br.com.branlycrm.pipeline.dto.response.LeadEventView;
import br.com.branlycrm.pipeline.dto.response.LeadView;
import br.com.branlycrm.pipeline.exceptions.LeadException;
import br.com.branlycrm.pipeline.repositories.PipelineRepository;
import br.com.branlycrm.pipeline.services.LeadService;
import br.com.branlycrm.provisioning.services.TenantProvisioningService;
import br.com.branlycrm.support.TestPostgres;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.services.TenantAccessService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
 * Leads and their timeline (CRM-031) in clinics created by the real provisioner: one open lead per
 * contact, transitions with events, the responsible's membership and clinic isolation.
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
class LeadPersistenceIT {
  @Container static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final Path SECRETS = temporaryDirectory();

  @Autowired TenantProvisioningService provisioning;
  @Autowired TenantAccessService access;
  @Autowired LeadService leads;
  @Autowired ContactRepository contacts;
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
    registry.add("app.tenancy.pool.max-per-tenant", () -> 8);
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
  void oneOpenLeadPerContactEvenUnderConcurrency() throws Exception {
    Clinic a = provision();
    UUID contact = contact(a, "(11) 98100-0001");
    int writers = 6;
    CyclicBarrier start = new CyclicBarrier(writers);
    List<Future<UUID>> results = new ArrayList<>();
    try (var pool = Executors.newFixedThreadPool(writers)) {
      for (int i = 0; i < writers; i++) {
        results.add(
            pool.submit(
                () -> {
                  start.await(10, TimeUnit.SECONDS);
                  try {
                    return leads.open(a.tenantId(), a.owner(), contact, null, null).id();
                  } catch (LeadException refused) {
                    assertThat(refused.reason()).isEqualTo(LeadException.Reason.ALREADY_OPEN);
                    return refused.existingLeadId();
                  }
                }));
      }
      List<UUID> ids = new ArrayList<>();
      for (var result : results) ids.add(result.get(30, TimeUnit.SECONDS));
      assertThat(ids).doesNotContainNull().containsOnly(ids.getFirst());
    }
    assertThat(owner(a, "SELECT count(*) FROM leads")).isEqualTo(1);
  }

  @Test
  void transitionsWriteTheTimelineAndAClosedLeadAllowsANewOne() throws Exception {
    Clinic a = provision();
    UUID contact = contact(a, "(11) 98100-0002");
    List<PipelineStage> stages = pipelines.get(a.access()).stages();
    UUID entry = stage(stages, StageKey.NEW);
    UUID scheduled = stage(stages, StageKey.SCHEDULED);
    UUID won = stage(stages, StageKey.WON);

    LeadView lead = leads.open(a.tenantId(), a.owner(), contact, null, a.owner());
    assertThat(lead.stageId()).isEqualTo(entry);
    assertThat(lead.status()).isEqualTo("OPEN");

    lead = leads.moveStage(a.tenantId(), a.owner(), lead.id(), lead.version(), scheduled);
    lead =
        leads.changeTemperature(
            a.tenantId(), a.owner(), lead.id(), lead.version(), Temperature.HOT);
    long current = lead.version();
    UUID id = lead.id();
    expect(
        () -> leads.moveStage(a.tenantId(), a.owner(), id, current, won),
        LeadException.Reason.STAGE_NOT_ALLOWED);
    expect(
        () -> leads.moveStage(a.tenantId(), a.owner(), id, current - 1, entry),
        LeadException.Reason.STALE);

    UUID reason =
        pipelines.lossReasons(a.access(), false).stream()
            .map(LossReason::id)
            .findFirst()
            .orElseThrow();
    lead = leads.lose(a.tenantId(), a.owner(), id, current, reason);
    assertThat(lead.status()).isEqualTo("LOST");
    assertThat(lead.lossReasonId()).isEqualTo(reason);
    assertThat(lead.closedAt()).isNotNull();
    long closed = lead.version();
    expect(
        () -> leads.changeTemperature(a.tenantId(), a.owner(), id, closed, Temperature.COLD),
        LeadException.Reason.CLOSED);

    assertThat(leads.history(a.tenantId(), a.owner(), id))
        .extracting(LeadEventView::type)
        .containsExactly(
            "CREATED", "RESPONSIBLE_CHANGED", "STAGE_CHANGED", "TEMPERATURE_CHANGED", "LOST");
    assertThat(leads.history(a.tenantId(), a.owner(), id))
        .allSatisfy(event -> assertThat(event.actorId()).isEqualTo(a.owner()));

    // RN-01: the contact can have a new lead once the previous one is closed.
    assertThat(leads.open(a.tenantId(), a.owner(), contact, null, null).id()).isNotEqualTo(id);
  }

  @Test
  void responsibleMustBeAnActiveMemberWhoWorksLeads() throws Exception {
    Clinic a = provision();
    UUID contact = contact(a, "(11) 98100-0003");
    UUID attendant = member(a, "ATTENDANT");
    UUID viewer = member(a, "VIEWER");
    UUID stranger = account();

    expect(
        () -> leads.open(a.tenantId(), a.owner(), contact, null, viewer),
        LeadException.Reason.RESPONSIBLE_INVALID);
    expect(
        () -> leads.open(a.tenantId(), a.owner(), contact, null, stranger),
        LeadException.Reason.RESPONSIBLE_INVALID);

    LeadView lead = leads.open(a.tenantId(), attendant, contact, null, attendant);
    assertThat(lead.responsibleId()).isEqualTo(attendant);
    // An attendant takes leads for itself but does not hand them to someone else.
    assertThatThrownBy(
            () -> leads.assign(a.tenantId(), attendant, lead.id(), lead.version(), a.owner()))
        .isInstanceOf(TenantAccessException.class);
    assertThat(
            leads.assign(a.tenantId(), a.owner(), lead.id(), lead.version(), null).responsibleId())
        .isNull();
    // A viewer neither opens nor loses leads.
    assertThatThrownBy(
            () -> leads.open(a.tenantId(), viewer, contact(a, "(11) 98100-0004"), null, null))
        .isInstanceOf(TenantAccessException.class);
  }

  @Test
  void anotherClinicsLeadIsInvisible() throws Exception {
    Clinic a = provision();
    Clinic b = provision();
    LeadView theirs =
        leads.open(b.tenantId(), b.owner(), contact(b, "(11) 98100-0005"), null, null);

    for (ThrowingCallable call :
        List.<ThrowingCallable>of(
            () -> leads.get(a.tenantId(), a.owner(), theirs.id()),
            () -> leads.history(a.tenantId(), a.owner(), theirs.id()),
            () ->
                leads.changeTemperature(
                    a.tenantId(), a.owner(), theirs.id(), 0, Temperature.HOT))) {
      expect(call, LeadException.Reason.NOT_FOUND);
    }
    // B's contact does not exist in A.
    expect(
        () -> leads.open(a.tenantId(), a.owner(), theirs.contactId(), null, null),
        LeadException.Reason.CONTACT_UNAVAILABLE);
    assertThat(owner(b, "SELECT count(*) FROM lead_events")).isEqualTo(1);
  }

  /**
   * The database refuses a lead whose stage disagrees with its status, and a loss without reason.
   */
  @Test
  void databaseKeepsLeadRules() throws Exception {
    Clinic a = provision();
    LeadView lead = leads.open(a.tenantId(), a.owner(), contact(a, "(11) 98100-0006"), null, null);
    for (String[] violation :
        List.of(
            new String[] {
              "an open lead in the won stage",
              "UPDATE leads SET stage_id = (SELECT id FROM pipeline_stages WHERE kind = 'WON')",
              "BRL03"
            },
            new String[] {
              "a loss without reason",
              "UPDATE leads SET status = 'LOST', closed_at = now(),"
                  + " stage_id = (SELECT id FROM pipeline_stages WHERE kind = 'LOST')",
              "23514"
            },
            new String[] {
              "an event rewritten by the runtime",
              "SET ROLE "
                  + a.access().database().databaseUser()
                  + "; UPDATE lead_events SET to_value = 'x'",
              "42501"
            })) {
      assertThat(sqlState(a, violation[1])).as(violation[0]).isEqualTo(violation[2]);
    }
    assertThat(lead.version()).isZero();
  }

  private record Clinic(UUID tenantId, UUID owner, ClinicAccess access) {}

  private static void expect(ThrowingCallable call, LeadException.Reason reason) {
    assertThatThrownBy(call)
        .isInstanceOf(LeadException.class)
        .extracting(error -> ((LeadException) error).reason())
        .isEqualTo(reason);
  }

  private static UUID stage(List<PipelineStage> stages, StageKey key) {
    return stages.stream().filter(s -> s.key() == key).findFirst().orElseThrow().id();
  }

  private UUID contact(Clinic clinic, String phone) {
    return contacts
        .create(
            clinic.access(),
            new ContactFields("Lead Sintético", PhoneNumber.parse(phone), null, null, null),
            ContactCreatedVia.MANUAL)
        .id();
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

  private UUID member(Clinic clinic, String role) {
    UUID subject = account();
    superuser.update(
        "INSERT INTO tenant_memberships (tenant_id, subject_id, role) VALUES (?, ?, ?)",
        clinic.tenantId(),
        subject,
        role);
    return subject;
  }

  private Clinic provision() {
    UUID owner = account();
    UUID tenant = UUID.randomUUID();
    String suffix = tenant.toString().replace("-", "");
    superuser.update(
        "INSERT INTO tenants (tenant_id, clinic_name, database_name, database_user,"
            + " credential_reference) VALUES (?, 'Clínica sintética', ?, ?, ?)",
        tenant,
        "clinic_" + suffix,
        "tenant_" + suffix,
        "TENANT_SECRET_" + suffix.toUpperCase(Locale.ROOT));
    superuser.update(
        "INSERT INTO tenant_provisioning_tasks (tenant_id, admin_subject_id, max_attempts)"
            + " VALUES (?, ?, 5)",
        tenant,
        owner);
    assertThat(provisioning.runPending()).isEqualTo(1);
    return new Clinic(tenant, owner, access.authorize(tenant, owner));
  }

  private static long owner(Clinic clinic, String sql) throws SQLException {
    try (Connection owner =
            TestPostgres.admin(POSTGRES, clinic.access().database().databaseName());
        var rows = owner.createStatement().executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static String sqlState(Clinic clinic, String sql) {
    try (Connection owner =
        TestPostgres.admin(POSTGRES, clinic.access().database().databaseName())) {
      owner.createStatement().execute(sql);
      return null;
    } catch (SQLException refused) {
      return refused.getSQLState();
    }
  }

  private static Path temporaryDirectory() {
    try {
      return Files.createTempDirectory("branly-lead-persistence-");
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
