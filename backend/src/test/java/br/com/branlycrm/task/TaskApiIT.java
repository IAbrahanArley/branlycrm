package br.com.branlycrm.task;

import static br.com.branlycrm.support.TestPostgres.PASSWORD;
import static br.com.branlycrm.support.TestPostgres.PROVISIONER;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.branlycrm.provisioning.services.TenantProvisioningService;
import br.com.branlycrm.support.Browser;
import br.com.branlycrm.support.MfaFixture;
import br.com.branlycrm.support.TestPostgres;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Tasks over real HTTP (CRM-040): due windows in the clinic time zone, links inside the clinic,
 * edit, completion and reopening with versions, roles, clinic isolation and grants. One test, so
 * each browser logs in once.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.docker.compose.enabled=false",
      "app.provisioning.enabled=true",
      "app.provisioning.scheduler.enabled=false",
      "app.provisioning.backoff=PT0S",
      MfaFixture.KEY_PROPERTY
    })
class TaskApiIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final Path SECRETS = temporaryDirectory();
  private static final String LOGIN_PASSWORD = "Synthetic tasks api secret 2026!";
  private static final String LEADS = "/v1/clinic/leads";

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;
  @Autowired TenantProvisioningService provisioning;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("app.tenancy.host", POSTGRES::getHost);
    registry.add("app.tenancy.port", () -> POSTGRES.getMappedPort(5432));
    registry.add("app.tenancy.secrets.directory", SECRETS::toString);
    registry.add("app.provisioning.admin.username", () -> PROVISIONER);
    registry.add("app.provisioning.admin.password", () -> PASSWORD);
    registry.add("app.provisioning.control-database", POSTGRES::getDatabaseName);
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
  void tasksDueFiltersRolesAndIsolation() throws Exception {
    UUID ownerA = account("owner-a@example.test");
    UUID clinicA = provision(ownerA);
    UUID clinicB = provision(account("owner-b@example.test"));
    UUID attendantId = account("attendant-a@example.test");
    member(clinicA, attendantId, "ATTENDANT");
    UUID viewerId = account("viewer-a@example.test");
    member(clinicA, viewerId, "VIEWER");
    Browser owner = selected("owner-a@example.test", clinicA);
    Browser attendant = selected("attendant-a@example.test", clinicA);
    Browser viewer = selected("viewer-a@example.test", clinicA);
    Browser ownerB = selected("owner-b@example.test", clinicB);

    String contact = contact(owner, "(11) 98600-0001");
    String other = contact(owner, "(11) 98600-0002");
    var opened = owner.postJson(LEADS, "{\"contactId\":\"" + other + "\"}", true);
    String otherLead = json(opened).get("id").asText();

    // Due windows in the clinic's time zone (America/Sao_Paulo by default, not UTC).
    ZoneId zone = ZoneId.of("America/Sao_Paulo");
    Instant endOfToday = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant();
    if (Duration.between(Instant.now(), endOfToday).compareTo(Duration.ofMinutes(5)) < 0) {
      // Too close to the clinic's midnight for stable windows: wait for the new day.
      Thread.sleep(Duration.between(Instant.now(), endOfToday).plusSeconds(5).toMillis());
      endOfToday = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant();
    }
    Instant now = Instant.now();
    Instant later = now.plus(Duration.between(now, endOfToday).dividedBy(2));
    String overdue = task(attendant, contact, null, attendantId, now.minus(Duration.ofHours(2)));
    String today = task(attendant, contact, null, attendantId, later);
    String upcoming = task(owner, contact, null, ownerA, endOfToday.plus(Duration.ofHours(1)));

    assertThat(ids(viewer.get(TASKS + "?due=OVERDUE"))).containsExactly(overdue);
    assertThat(ids(viewer.get(TASKS + "?due=TODAY"))).containsExactly(today);
    assertThat(ids(viewer.get(TASKS + "?due=UPCOMING"))).containsExactly(upcoming);
    assertThat(ids(attendant.get(TASKS + "?responsibleId=mine"))).containsExactly(overdue, today);
    assertThat(ids(owner.get(TASKS))).containsExactly(overdue, today, upcoming);
    assertThat(owner.get(TASKS + "?due=SOON").statusCode()).isEqualTo(400);
    assertThat(owner.get(TASKS + "?due=TODAY&status=DONE").statusCode()).isEqualTo(400);
    assertThat(
            owner
                .postJson(
                    TASKS,
                    body(
                        "Ligar",
                        contact,
                        null,
                        ownerA,
                        Instant.parse("2200-01-01T00:00:00Z"),
                        null),
                    true)
                .statusCode())
        .isEqualTo(400);

    // Links stay inside the clinic: the lead belongs to the contact; responsibles work tasks.
    assertThat(
            code(
                owner.postJson(
                    TASKS, body("Ligar", contact, otherLead, ownerA, later, null), true)))
        .isEqualTo("TASK_LEAD_MISMATCH");
    assertThat(
            code(owner.postJson(TASKS, body("Ligar", contact, null, viewerId, later, null), true)))
        .isEqualTo("TASK_RESPONSIBLE_INVALID");
    assertThat(
            owner.postJson(TASKS, body(" ", contact, null, ownerA, later, null), true).statusCode())
        .isEqualTo(400);
    String linked = task(owner, other, otherLead, ownerA, later);
    assertThat(ids(owner.get(TASKS + "?leadId=" + otherLead))).containsExactly(linked);

    // Edit at the version read, complete, reopen.
    var edited =
        attendant.putJson(
            TASKS + "/" + today,
            body("Confirmar horário", null, null, attendantId, later, 0),
            true);
    assertThat(edited.statusCode()).as(edited.body()).isEqualTo(200);
    assertThat(json(edited).get("title").asText()).isEqualTo("Confirmar horário");
    assertThat(
            code(
                attendant.putJson(
                    TASKS + "/" + today, body("Outro", null, null, attendantId, later, 0), true)))
        .isEqualTo("TASK_STALE");
    var done =
        attendant.postJson(
            TASKS + "/" + today + "/completion", "{\"version\":1,\"note\":\"Confirmado\"}", true);
    assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
    assertThat(json(done).get("status").asText()).isEqualTo("DONE");
    assertThat(
            code(attendant.postJson(TASKS + "/" + today + "/completion", "{\"version\":2}", true)))
        .isEqualTo("TASK_DONE");
    assertThat(ids(viewer.get(TASKS + "?status=DONE"))).containsExactly(today);
    var reopened = owner.postJson(TASKS + "/" + today + "/reopening", "{\"version\":2}", true);
    assertThat(reopened.statusCode()).as(reopened.body()).isEqualTo(200);
    assertThat(json(reopened).get("completedAt").isNull()).isTrue();

    // The viewer reads but does not write.
    assertThat(
            code(viewer.postJson(TASKS, body("Negada", contact, null, ownerA, later, null), true)))
        .isEqualTo("CLINIC_ACCESS_DENIED");
    assertThat(code(viewer.postJson(TASKS + "/" + today + "/completion", "{\"version\":3}", true)))
        .isEqualTo("CLINIC_ACCESS_DENIED");

    // Clinic B neither lists, reads nor changes A's tasks, nor links A's contact.
    assertThat(json(ownerB.get(TASKS)).get("total").asLong()).isZero();
    assertThat(code(ownerB.get(TASKS + "/" + today))).isEqualTo("TASK_NOT_FOUND");
    assertThat(
            code(
                ownerB.putJson(
                    TASKS + "/" + today, body("Invasão", null, null, ownerA, later, 3), true)))
        .isEqualTo("TASK_NOT_FOUND");
    assertThat(code(ownerB.postJson(TASKS + "/" + today + "/completion", "{\"version\":3}", true)))
        .isEqualTo("TASK_NOT_FOUND");
    assertThat(code(ownerB.postJson(TASKS + "/" + today + "/reopening", "{\"version\":3}", true)))
        .isEqualTo("TASK_NOT_FOUND");
    UUID ownerBId =
        jdbc.queryForObject(
            "SELECT subject_id FROM user_accounts WHERE email = 'owner-b@example.test'",
            UUID.class);
    assertThat(
            code(
                ownerB.putJson(
                    TASKS + "/" + today, body("Invasão", null, null, ownerBId, later, 3), true)))
        .isEqualTo("TASK_NOT_FOUND");
    assertThat(
            code(
                ownerB.postJson(
                    TASKS, body("Invasão", contact, null, ownerBId, later, null), true)))
        .isEqualTo("TASK_CONTACT_UNAVAILABLE");

    // The runtime never deletes tasks nor moves them to another contact.
    String suffix = clinicA.toString().replace("-", "");
    String role = "tenant_" + suffix;
    try (Connection admin = TestPostgres.admin(POSTGRES, "clinic_" + suffix)) {
      for (String check :
          List.of(
              "has_table_privilege('" + role + "', 'tasks', 'DELETE,TRUNCATE')",
              "has_column_privilege('" + role + "', 'tasks', 'contact_id', 'UPDATE')",
              "has_column_privilege('" + role + "', 'tasks', 'lead_id', 'UPDATE')",
              "has_column_privilege('" + role + "', 'tasks', 'created_by', 'UPDATE')")) {
        try (var rows = admin.createStatement().executeQuery("SELECT " + check)) {
          rows.next();
          assertThat(rows.getBoolean(1)).as(check).isFalse();
        }
      }
    }
  }

  private static final String TASKS = "/v1/clinic/tasks";

  private static String body(
      String title,
      String contactId,
      String leadId,
      UUID responsible,
      Instant due,
      Integer version) {
    StringBuilder json = new StringBuilder("{\"title\":\"").append(title).append("\"");
    if (contactId != null) json.append(",\"contactId\":\"").append(contactId).append("\"");
    if (leadId != null) json.append(",\"leadId\":\"").append(leadId).append("\"");
    json.append(",\"responsibleId\":\"").append(responsible).append("\"");
    json.append(",\"dueAt\":\"").append(due).append("\"");
    if (version != null) json.append(",\"version\":").append(version);
    return json.append("}").toString();
  }

  private static String task(
      Browser browser, String contactId, String leadId, UUID responsible, Instant due)
      throws Exception {
    var created =
        browser.postJson(TASKS, body("Retorno", contactId, leadId, responsible, due, null), true);
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    return json(created).get("id").asText();
  }

  private static List<String> ids(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    List<String> ids = new ArrayList<>();
    json(response).get("items").forEach(item -> ids.add(item.get("id").asText()));
    return ids;
  }

  private String contact(Browser browser, String phone) throws Exception {
    var created =
        browser.postJson(
            "/v1/clinic/contacts",
            "{\"name\":\"Lead Sintético\",\"phone\":\"" + phone + "\"}",
            true);
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    return json(created).get("id").asText();
  }

  private static JsonNode json(HttpResponse<String> response) {
    return Browser.JSON.readTree(response.body());
  }

  private static String code(HttpResponse<String> response) {
    return json(response).get("code").asText();
  }

  private Browser selected(String email, UUID clinic) throws Exception {
    Browser browser = new Browser(port);
    browser.csrf();
    assertThat(browser.login(email, LOGIN_PASSWORD).statusCode()).isEqualTo(204);
    browser.csrf();
    MfaFixture.complete(browser, jdbc);
    var selection = browser.put("/v1/auth/clinic/" + clinic, true);
    assertThat(selection.statusCode()).as(selection.body()).isEqualTo(200);
    return browser;
  }

  private UUID account(String email) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO user_accounts (subject_id, email, display_name, password_hash, email_verified)"
            + " VALUES (?, ?, 'Synthetic', ?, true)",
        id,
        email,
        encoder.encode(LOGIN_PASSWORD));
    return id;
  }

  private UUID provision(UUID owner) {
    UUID tenant = UUID.randomUUID();
    String suffix = tenant.toString().replace("-", "");
    jdbc.update(
        "INSERT INTO tenants (tenant_id, clinic_name, database_name, database_user,"
            + " credential_reference) VALUES (?, 'Clínica sintética', ?, ?, ?)",
        tenant,
        "clinic_" + suffix,
        "tenant_" + suffix,
        "TENANT_SECRET_" + suffix.toUpperCase(Locale.ROOT));
    jdbc.update(
        "INSERT INTO tenant_provisioning_tasks (tenant_id, admin_subject_id, max_attempts)"
            + " VALUES (?, ?, 5)",
        tenant,
        owner);
    assertThat(provisioning.runPending()).isEqualTo(1);
    return tenant;
  }

  private void member(UUID tenant, UUID subject, String role) {
    jdbc.update(
        "INSERT INTO tenant_memberships (tenant_id, subject_id, role) VALUES (?, ?, ?)",
        tenant,
        subject,
        role);
  }

  private static Path temporaryDirectory() {
    try {
      return Files.createTempDirectory("branly-task-api-");
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
