package br.com.branlycrm.pipeline;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * Leads API over real HTTP (CRM-032) on clinics created by the real provisioner: the main flow, the
 * loss rule, roles, clinic isolation and concurrent moves. One test, so each browser logs in once
 * (the login limit is per minute).
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
class LeadApiIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final Path SECRETS = temporaryDirectory();
  private static final String LOGIN_PASSWORD = "Synthetic leads api secret 2026!";
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
  void leadFlowRolesIsolationAndConcurrentMoves() throws Exception {
    UUID clinicA = provision(account("owner-a@example.test"));
    UUID clinicB = provision(account("owner-b@example.test"));
    UUID attendantId = account("attendant-a@example.test");
    member(clinicA, attendantId, "ATTENDANT");
    member(clinicA, account("viewer-a@example.test"), "VIEWER");
    Browser owner = selected("owner-a@example.test", clinicA);
    Browser attendant = selected("attendant-a@example.test", clinicA);
    Browser viewer = selected("viewer-a@example.test", clinicA);
    Browser ownerB = selected("owner-b@example.test", clinicB);

    JsonNode stages = json(owner.get("/v1/clinic/pipeline")).get("stages");
    String entry = stages.get(0).get("id").asText();
    String scheduled = stages.get(2).get("id").asText();
    String reason = json(owner.get("/v1/clinic/loss-reasons")).get(0).get("id").asText();
    String contact = contact(owner, "(11) 98200-0001");

    // Open, and the second opening points to the open lead.
    var opened = owner.postJson(LEADS, "{\"contactId\":\"" + contact + "\"}", true);
    assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
    String lead = json(opened).get("id").asText();
    assertThat(json(opened).get("stageId").asText()).isEqualTo(entry);
    var again = owner.postJson(LEADS, "{\"contactId\":\"" + contact + "\"}", true);
    assertThat(code(again)).isEqualTo("LEAD_ALREADY_OPEN");
    assertThat(json(again).get("leadId").asText()).isEqualTo(lead);

    JsonNode listed = json(owner.get(LEADS + "?stageId=" + entry)).get("items");
    assertThat(listed.get(0).get("id").asText()).isEqualTo(lead);
    assertThat(listed.get(0).get("contactName").asText()).isEqualTo("Lead Sintético");

    // Transitions at the version read; a stale version is refused.
    var moved = owner.putJson(LEADS + "/" + lead + "/stage", stage(scheduled, 0), true);
    assertThat(moved.statusCode()).as(moved.body()).isEqualTo(200);
    assertThat(code(owner.putJson(LEADS + "/" + lead + "/stage", stage(entry, 0), true)))
        .isEqualTo("LEAD_STALE");
    var hot =
        owner.putJson(
            LEADS + "/" + lead + "/temperature", "{\"temperature\":\"HOT\",\"version\":1}", true);
    assertThat(hot.statusCode()).as(hot.body()).isEqualTo(200);
    var taken =
        attendant.putJson(
            LEADS + "/" + lead + "/responsible",
            "{\"responsibleId\":\"" + attendantId + "\",\"version\":2}",
            true);
    assertThat(taken.statusCode()).as(taken.body()).isEqualTo(200);
    assertThat(
            attendant
                .postJson(LEADS + "/" + lead + "/notes", "{\"body\":\"Ligar amanhã.\"}", true)
                .statusCode())
        .isEqualTo(201);
    assertThat(json(viewer.get(LEADS + "/" + lead + "/notes")).get(0).get("body").asText())
        .isEqualTo("Ligar amanhã.");

    // A loss needs its reason.
    var noReason = owner.postJson(LEADS + "/" + lead + "/loss", "{\"version\":3}", true);
    assertThat(code(noReason)).isEqualTo("INVALID_INPUT");
    assertThat(json(noReason).get("fields").toString()).contains("lossReasonId");
    var lost =
        owner.postJson(
            LEADS + "/" + lead + "/loss",
            "{\"lossReasonId\":\"" + reason + "\",\"version\":3}",
            true);
    assertThat(lost.statusCode()).as(lost.body()).isEqualTo(200);
    assertThat(json(lost).get("status").asText()).isEqualTo("LOST");
    assertThat(json(owner.get(LEADS)).get("total").asLong()).isZero();
    assertThat(json(owner.get(LEADS + "?status=LOST")).get("total").asLong()).isOne();
    List<String> types = new ArrayList<>();
    json(owner.get(LEADS + "/" + lead + "/events")).forEach(e -> types.add(e.get("type").asText()));
    assertThat(types)
        .containsExactly(
            "CREATED", "STAGE_CHANGED", "TEMPERATURE_CHANGED", "RESPONSIBLE_CHANGED", "LOST");

    // Roles: the viewer reads but changes nothing.
    String other = contact(owner, "(11) 98200-0002");
    assertThat(code(viewer.postJson(LEADS, "{\"contactId\":\"" + other + "\"}", true)))
        .isEqualTo("CLINIC_ACCESS_DENIED");
    assertThat(code(viewer.postJson(LEADS + "/" + lead + "/notes", "{\"body\":\"x\"}", true)))
        .isEqualTo("CLINIC_ACCESS_DENIED");
    assertThat(viewer.get(LEADS + "?status=LOST").statusCode()).isEqualTo(200);

    // Isolation: B never sees A's lead.
    assertThat(code(ownerB.get(LEADS + "/" + lead))).isEqualTo("LEAD_NOT_FOUND");
    assertThat(code(ownerB.get(LEADS + "/" + lead + "/events"))).isEqualTo("LEAD_NOT_FOUND");
    assertThat(json(ownerB.get(LEADS + "?status=LOST")).get("total").asLong()).isZero();
    for (var change :
        List.of(
            ownerB.putJson(LEADS + "/" + lead + "/stage", stage(scheduled, 4), true),
            ownerB.putJson(
                LEADS + "/" + lead + "/temperature",
                "{\"temperature\":\"COLD\",\"version\":4}",
                true),
            ownerB.putJson(
                LEADS + "/" + lead + "/responsible",
                "{\"responsibleId\":null,\"version\":4}",
                true),
            ownerB.postJson(
                LEADS + "/" + lead + "/loss",
                "{\"lossReasonId\":\"" + reason + "\",\"version\":4}",
                true),
            ownerB.postJson(LEADS + "/" + lead + "/notes", "{\"body\":\"Invasão\"}", true),
            ownerB.get(LEADS + "/" + lead + "/notes"))) {
      assertThat(code(change)).as(change.uri().getPath()).isEqualTo("LEAD_NOT_FOUND");
    }
    for (String filter :
        List.of(
            "contactId=" + contact, "stageId=" + scheduled, "status=LOST&contactId=" + contact)) {
      assertThat(json(ownerB.get(LEADS + "?" + filter)).get("total").asLong()).as(filter).isZero();
    }

    // Concurrent moves at one version: one wins, the other is stale, and no event is lost.
    String racing =
        json(owner.postJson(LEADS, "{\"contactId\":\"" + other + "\"}", true)).get("id").asText();
    CyclicBarrier start = new CyclicBarrier(2);
    List<Future<Integer>> moves = new ArrayList<>();
    try (var pool = Executors.newFixedThreadPool(2)) {
      for (Browser browser : List.of(owner, attendant)) {
        moves.add(
            pool.submit(
                () -> {
                  start.await(10, TimeUnit.SECONDS);
                  return browser
                      .putJson(LEADS + "/" + racing + "/stage", stage(scheduled, 0), true)
                      .statusCode();
                }));
      }
      List<Integer> statuses = new ArrayList<>();
      for (var move : moves) statuses.add(move.get(30, TimeUnit.SECONDS));
      assertThat(statuses).containsExactlyInAnyOrder(200, 409);
    }
    List<String> raced = new ArrayList<>();
    json(owner.get(LEADS + "/" + racing + "/events"))
        .forEach(e -> raced.add(e.get("type").asText()));
    assertThat(raced).containsExactly("CREATED", "STAGE_CHANGED");
    assertThat(json(owner.get(LEADS + "/" + racing)).get("version").asLong()).isOne();
  }

  private static String stage(String stageId, long version) {
    return "{\"stageId\":\"" + stageId + "\",\"version\":" + version + "}";
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
      return Files.createTempDirectory("branly-lead-api-");
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
