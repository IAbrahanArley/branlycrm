package br.com.branlycrm.timeline;

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
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
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
 * Contact timeline over real HTTP (CRM-023): two leads, a note, a cancelled sale and a completed
 * task come back newest first with their authors, paging is stable, every role reads it and another
 * clinic's contact answers 404. One test, so each browser logs in once.
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
class ContactTimelineIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final Path SECRETS = temporaryDirectory();
  private static final String LOGIN_PASSWORD = "Synthetic timeline api secret 2026!";
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

  private static final String TIMELINE = "/v1/clinic/contacts/%s/timeline";

  @Test
  void contactTimelineJoinsLeadsNotesSalesAndTasksNewestFirst() throws Exception {
    UUID ownerA = account("owner-a@example.test");
    UUID clinicA = provision(ownerA);
    UUID clinicB = provision(account("owner-b@example.test"));
    member(clinicA, account("viewer-a@example.test"), "VIEWER");
    Browser owner = selected("owner-a@example.test", clinicA);
    Browser viewer = selected("viewer-a@example.test", clinicA);
    Browser ownerB = selected("owner-b@example.test", clinicB);

    String someone = contact(owner, "(11) 98700-0101");
    String first = lead(owner, someone, "");
    var note =
        owner.postJson(LEADS + "/" + first + "/notes", "{\"body\":\"Nota sintética\"}", true);
    assertThat(note.statusCode()).as(note.body()).isEqualTo(201);
    String reason = json(owner.get("/v1/clinic/loss-reasons")).get(0).get("id").asText();
    ok(
        owner.postJson(
            LEADS + "/" + first + "/loss",
            "{\"lossReasonId\":\"" + reason + "\",\"version\":0}",
            true));
    String second = lead(owner, someone, "");
    String saleId =
        sale(
            owner,
            second,
            created(owner, "/v1/clinic/procedures", procedure("Limpeza")),
            10000,
            null);
    ok(
        owner.postJson(
            "/v1/clinic/sales/" + saleId + "/cancellation",
            "{\"reason\":\"Desistiu\",\"version\":0}",
            true));
    String task =
        created(
            owner,
            "/v1/clinic/tasks",
            "{\"title\":\"Retorno\",\"contactId\":\""
                + someone
                + "\",\"leadId\":\""
                + second
                + "\",\"responsibleId\":\""
                + ownerA
                + "\",\"dueAt\":\""
                + Instant.now().plus(Duration.ofDays(1))
                + "\"}");
    ok(owner.postJson("/v1/clinic/tasks/" + task + "/completion", "{\"version\":0}", true));
    // A task without a lead, completed and then reopened: only its creation stays.
    String loose =
        created(
            owner,
            "/v1/clinic/tasks",
            "{\"title\":\"Ligar\",\"contactId\":\""
                + someone
                + "\",\"responsibleId\":\""
                + ownerA
                + "\",\"dueAt\":\""
                + Instant.now().plus(Duration.ofDays(2))
                + "\"}");
    ok(owner.postJson("/v1/clinic/tasks/" + loose + "/completion", "{\"version\":0}", true));
    ok(owner.postJson("/v1/clinic/tasks/" + loose + "/reopening", "{\"version\":1}", true));

    JsonNode all = ok(viewer.get(TIMELINE.formatted(someone) + "?pageSize=100"));
    JsonNode items = all.get("items");
    assertThat(all.get("total").asLong()).isEqualTo(items.size());
    List<String> kinds = new ArrayList<>();
    items.forEach(item -> kinds.add(item.get("kind").asText()));
    assertThat(kinds)
        .contains("LEAD_EVENT", "NOTE", "SALE_EVENT", "TASK_CREATED", "TASK_COMPLETED");
    JsonNode newest = items.get(0);
    assertThat(newest.get("kind").asText()).isEqualTo("TASK_CREATED");
    assertThat(newest.get("id").asText()).isEqualTo(loose);
    assertThat(newest.get("leadId").isNull()).isTrue();
    items.forEach(
        item ->
            assertThat(item.get("kind").asText() + item.get("id").asText())
                .isNotEqualTo("TASK_COMPLETED" + loose));
    assertThat(items.get(1).get("kind").asText()).isEqualTo("TASK_COMPLETED");
    JsonNode oldest = items.get(items.size() - 1);
    assertThat(oldest.get("kind").asText()).isEqualTo("LEAD_EVENT");
    assertThat(oldest.get("type").asText()).isEqualTo("CREATED");
    assertThat(oldest.get("leadId").asText()).isEqualTo(first);
    for (int i = 1; i < items.size(); i++) {
      assertThat(Instant.parse(items.get(i - 1).get("occurredAt").asText()))
          .isAfterOrEqualTo(Instant.parse(items.get(i).get("occurredAt").asText()));
    }
    items.forEach(item -> assertThat(item.get("actorId").asText()).isEqualTo(ownerA.toString()));
    JsonNode noted = find(items, "NOTE", null);
    assertThat(noted.get("text").asText()).isEqualTo("Nota sintética");
    assertThat(noted.get("leadId").asText()).isEqualTo(first);
    JsonNode cancelled = find(items, "SALE_EVENT", "CANCELLED");
    assertThat(cancelled.get("saleId").asText()).isEqualTo(saleId);
    assertThat(cancelled.get("text").asText()).isEqualTo("Desistiu");
    assertThat(find(items, "SALE_EVENT", "CREATED").get("amountCents").asLong()).isEqualTo(10000);
    assertThat(find(items, "LEAD_EVENT", "LOST").get("lossReasonId").asText()).isEqualTo(reason);
    assertThat(find(items, "TASK_COMPLETED", null).get("text").asText()).isEqualTo("Retorno");

    // Pages of 3 rebuild the same list, in the same order, without overlap.
    List<String> paged = new ArrayList<>();
    for (int page = 1; paged.size() < items.size(); page++) {
      JsonNode slice =
          ok(owner.get(TIMELINE.formatted(someone) + "?pageSize=3&page=" + page)).get("items");
      assertThat(slice.size()).isPositive();
      slice.forEach(item -> paged.add(item.get("kind").asText() + item.get("id").asText()));
    }
    List<String> expected = new ArrayList<>();
    items.forEach(item -> expected.add(item.get("kind").asText() + item.get("id").asText()));
    assertThat(paged).isEqualTo(expected);

    // A contact without history has an empty timeline.
    JsonNode empty = ok(owner.get(TIMELINE.formatted(contact(owner, "(11) 98700-0102"))));
    assertThat(empty.get("total").asLong()).isZero();

    // Clinic B never sees A's contact, and its own contact never shows A's history.
    var foreign = ownerB.get(TIMELINE.formatted(someone));
    assertThat(foreign.statusCode()).as(foreign.body()).isEqualTo(404);
    assertThat(code(foreign)).isEqualTo("CONTACT_NOT_FOUND");
    String sameNumberInB = contact(ownerB, "(11) 98700-0101");
    assertThat(ok(ownerB.get(TIMELINE.formatted(sameNumberInB))).get("total").asLong()).isZero();

    // Unknown contact, malformed id and invalid paging.
    var unknown = owner.get(TIMELINE.formatted(UUID.randomUUID()));
    assertThat(unknown.statusCode()).isEqualTo(404);
    assertThat(code(unknown)).isEqualTo("CONTACT_NOT_FOUND");
    assertThat(owner.get(TIMELINE.formatted("not-a-uuid")).statusCode()).isEqualTo(400);
    var badSize = owner.get(TIMELINE.formatted(someone) + "?pageSize=0");
    assertThat(badSize.statusCode()).isEqualTo(400);
    assertThat(json(badSize).get("fields").get(0).asText()).isEqualTo("pageSize");
  }

  private static JsonNode find(JsonNode items, String kind, String type) {
    for (JsonNode item : items) {
      if (item.get("kind").asText().equals(kind)
          && (type == null || item.get("type").asText().equals(type))) {
        return item;
      }
    }
    throw new AssertionError("No " + kind + " " + type);
  }

  private static String procedure(String name) {
    return "{\"name\":\"" + name + "\",\"referencePriceCents\":10000}";
  }

  private static String created(Browser browser, String path, String body) throws Exception {
    var created = browser.postJson(path, body, true);
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    return json(created).get("id").asText();
  }

  private static String lead(Browser browser, String contactId, String extra) throws Exception {
    return created(browser, LEADS, "{\"contactId\":\"" + contactId + "\"" + extra + "}");
  }

  private static String sale(
      Browser browser, String leadId, String procedureId, long cents, String soldOn)
      throws Exception {
    return created(
        browser,
        LEADS + "/" + leadId + "/sale",
        "{\"procedureId\":\""
            + procedureId
            + "\",\"amountCents\":"
            + cents
            + (soldOn == null ? "" : ",\"soldOn\":\"" + soldOn + "\"")
            + ",\"version\":0}");
  }

  private static JsonNode ok(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return json(response);
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
      return Files.createTempDirectory("branly-timeline-api-");
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
