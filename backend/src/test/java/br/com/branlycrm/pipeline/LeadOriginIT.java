package br.com.branlycrm.pipeline;

import static br.com.branlycrm.support.TestPostgres.PASSWORD;
import static br.com.branlycrm.support.TestPostgres.PROVISIONER;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.branlycrm.pipeline.domain.LeadOrigin;
import br.com.branlycrm.pipeline.repositories.LeadRepository;
import br.com.branlycrm.provisioning.services.TenantProvisioningService;
import br.com.branlycrm.support.Browser;
import br.com.branlycrm.support.MfaFixture;
import br.com.branlycrm.support.TestPostgres;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.domain.ClinicPermission;
import br.com.branlycrm.tenant.services.TenantAccessService;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
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
 * Lead origin over real HTTP (CRM-090): unidentified or manual first origin, later touches that
 * never overwrite it, manual corrections with timeline events (also on closed leads), roles, clinic
 * isolation and the runtime grants. One test, so each browser logs in once.
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
class LeadOriginIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final Path SECRETS = temporaryDirectory();
  private static final String LOGIN_PASSWORD = "Synthetic origin api secret 2026!";
  private static final String LEADS = "/v1/clinic/leads";

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;
  @Autowired TenantProvisioningService provisioning;
  @Autowired TenantAccessService tenantAccess;
  @Autowired LeadRepository repository;

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
  void originIsKeptCorrectedByHandAndTouchedLater() throws Exception {
    UUID ownerA = account("owner-a@example.test");
    UUID clinicA = provision(ownerA);
    UUID clinicB = provision(account("owner-b@example.test"));
    member(clinicA, account("attendant-a@example.test"), "ATTENDANT");
    member(clinicA, account("viewer-a@example.test"), "VIEWER");
    Browser owner = selected("owner-a@example.test", clinicA);
    Browser attendant = selected("attendant-a@example.test", clinicA);
    Browser viewer = selected("viewer-a@example.test", clinicA);
    Browser ownerB = selected("owner-b@example.test", clinicB);

    // Without an origin the lead starts unidentified; with one, it is manual.
    String first = contact(owner, "(11) 98400-0001");
    var plain = owner.postJson(LEADS, "{\"contactId\":\"" + first + "\"}", true);
    assertThat(plain.statusCode()).as(plain.body()).isEqualTo(201);
    assertThat(json(plain).get("origin").get("type").asText()).isEqualTo("UNIDENTIFIED");
    assertThat(json(plain).get("origin").get("source").asText()).isEqualTo("NONE");
    String second = contact(owner, "(11) 98400-0002");
    var referred =
        attendant.postJson(
            LEADS,
            "{\"contactId\":\""
                + second
                + "\",\"origin\":{\"type\":\"REFERRAL\",\"title\":\"Indicação da Ana\"}}",
            true);
    assertThat(referred.statusCode()).as(referred.body()).isEqualTo(201);
    String lead = json(referred).get("id").asText();
    assertThat(json(referred).get("origin").get("source").asText()).isEqualTo("MANUAL");
    var badCode =
        owner.postJson(
            LEADS,
            "{\"contactId\":\"" + first + "\",\"origin\":{\"type\":\"GOOGLE\",\"code\":\"a b\"}}",
            true);
    assertThat(badCode.statusCode()).isEqualTo(400);
    JsonNode listed = json(owner.get(LEADS + "?contactId=" + second)).get("items").get(0);
    assertThat(listed.get("origin").get("type").asText()).isEqualTo("REFERRAL");

    // A later arrival is a touch: the lead keeps its first origin (RN-02).
    ClinicAccess access = tenantAccess.require(clinicA, ownerA, ClinicPermission.LEAD_READ);
    var touch =
        repository.recordTouch(
            access,
            UUID.fromString(second),
            new LeadOrigin(
                LeadOrigin.Type.PAID_TRAFFIC,
                LeadOrigin.Source.AD,
                "120000000000001",
                null,
                "Clareamento em novembro",
                "synthetic-click-id"));
    assertThat(touch.leadId()).isEqualTo(UUID.fromString(lead));
    assertThat(json(owner.get(LEADS + "/" + lead)).get("origin").get("type").asText())
        .isEqualTo("REFERRAL");
    var touches = viewer.get("/v1/clinic/contacts/" + second + "/origin-touches");
    assertThat(touches.statusCode()).as(touches.body()).isEqualTo(200);
    assertThat(json(touches).get(0).get("origin").get("adId").asText())
        .isEqualTo("120000000000001");
    assertThat(touches.body()).doesNotContain("synthetic-click-id");
    assertThat(json(ownerB.get("/v1/clinic/contacts/" + second + "/origin-touches"))).isEmpty();

    // Manual correction, also after the lead is closed, with a timeline event.
    String correct = LEADS + "/" + lead + "/origin";
    String paid = "{\"type\":\"PAID_TRAFFIC\",\"code\":\"BOTOX01\",\"version\":0}";
    assertThat(code(viewer.putJson(correct, paid, true))).isEqualTo("CLINIC_ACCESS_DENIED");
    assertThat(code(ownerB.putJson(correct, paid, true))).isEqualTo("LEAD_NOT_FOUND");
    var corrected = attendant.putJson(correct, paid, true);
    assertThat(corrected.statusCode()).as(corrected.body()).isEqualTo(200);
    assertThat(json(corrected).get("origin").get("code").asText()).isEqualTo("BOTOX01");
    assertThat(code(owner.putJson(correct, paid, true))).isEqualTo("LEAD_STALE");
    var same = owner.putJson(correct, paid.replace(":0", ":1"), true);
    assertThat(json(same).get("version").asLong()).isOne();

    String reason = json(owner.get("/v1/clinic/loss-reasons")).get(0).get("id").asText();
    var lost =
        owner.postJson(
            LEADS + "/" + lead + "/loss",
            "{\"lossReasonId\":\"" + reason + "\",\"version\":1}",
            true);
    assertThat(lost.statusCode()).as(lost.body()).isEqualTo(200);
    var afterClose = owner.putJson(correct, "{\"type\":\"INSTAGRAM_ORGANIC\",\"version\":2}", true);
    assertThat(afterClose.statusCode()).as(afterClose.body()).isEqualTo(200);
    assertThat(json(afterClose).get("status").asText()).isEqualTo("LOST");

    JsonNode events = json(owner.get(LEADS + "/" + lead + "/events"));
    List<String> origins = new ArrayList<>();
    events.forEach(
        event -> {
          if ("ORIGIN_CHANGED".equals(event.get("type").asText())) {
            origins.add(event.get("fromValue").asText() + ">" + event.get("toValue").asText());
          }
        });
    assertThat(origins).containsExactly("REFERRAL>PAID_TRAFFIC", "PAID_TRAFFIC>INSTAGRAM_ORGANIC");

    // The runtime writes only the origin columns and never rewrites touches.
    String suffix = clinicA.toString().replace("-", "");
    String role = "tenant_" + suffix;
    try (Connection admin = TestPostgres.admin(POSTGRES, "clinic_" + suffix)) {
      for (String check :
          List.of(
              "has_table_privilege('" + role + "', 'origin_touches', 'UPDATE,DELETE,TRUNCATE')",
              "has_any_column_privilege('" + role + "', 'origin_touches', 'UPDATE')",
              "has_column_privilege('" + role + "', 'leads', 'contact_id', 'UPDATE')")) {
        try (var rows = admin.createStatement().executeQuery("SELECT " + check)) {
          rows.next();
          assertThat(rows.getBoolean(1)).as(check).isFalse();
        }
      }
    }
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
      return Files.createTempDirectory("branly-lead-origin-");
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
