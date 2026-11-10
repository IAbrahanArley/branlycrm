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
 * Funnel and loss reasons over real HTTP (cookies, CSRF, MFA gate) on clinics created by the real
 * provisioner (CRM-030). One test, so every browser logs in once (the login limit is per minute).
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
class PipelineApiIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final Path SECRETS = temporaryDirectory();
  private static final String LOGIN_PASSWORD = "Synthetic pipeline api secret 2026!";

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
  void everyRoleReadsItsOwnClinicFunnelAndReasons() throws Exception {
    UUID ownerA = account("owner-a@example.test");
    UUID ownerB = account("owner-b@example.test");
    UUID clinicA = provision(ownerA);
    UUID clinicB = provision(ownerB);
    for (String role : List.of("MANAGER", "ATTENDANT", "VIEWER")) {
      member(clinicA, account(role.toLowerCase(Locale.ROOT) + "-a@example.test"), role);
    }
    UUID revoked = account("revoked-a@example.test");
    member(clinicA, revoked, "ATTENDANT");

    List<Browser> roles = new ArrayList<>();
    for (String email :
        List.of(
            "owner-a@example.test",
            "manager-a@example.test",
            "attendant-a@example.test",
            "viewer-a@example.test")) {
      roles.add(selected(email, clinicA));
    }
    for (Browser browser : roles) {
      var funnel = browser.get("/v1/clinic/pipeline");
      assertThat(funnel.statusCode()).as(funnel.body()).isEqualTo(200);
      JsonNode stages = json(funnel).get("stages");
      assertThat(stages.size()).isEqualTo(6);
      assertThat(stages.get(0).get("key").asText()).isEqualTo("NEW");
      assertThat(stages.get(0).get("position").asInt()).isOne();
      assertThat(stages.get(4).get("kind").asText()).isEqualTo("WON");
      assertThat(stages.get(4).get("position").isNull()).isTrue();
      assertThat(stages.get(5).get("kind").asText()).isEqualTo("LOST");
      var reasons = browser.get("/v1/clinic/loss-reasons");
      assertThat(reasons.statusCode()).isEqualTo(200);
      assertThat(json(reasons).size()).isEqualTo(7);
    }

    // Each clinic sees its own funnel: the ids are different.
    Browser b = selected("owner-b@example.test", clinicB);
    assertThat(json(b.get("/v1/clinic/pipeline")).get("id").asText())
        .isNotEqualTo(json(roles.getFirst().get("/v1/clinic/pipeline")).get("id").asText());

    Browser owner = roles.getFirst();
    var invalid = owner.get("/v1/clinic/loss-reasons?includeArchived=yes");
    assertThat(invalid.statusCode()).isEqualTo(400);
    assertThat(json(invalid).get("fields").toString()).contains("includeArchived");
    assertThat(owner.get("/v1/clinic/loss-reasons?includeArchived=true").statusCode())
        .isEqualTo(200);

    Browser loose = loggedIn("revoked-a@example.test");
    assertThat(code(loose.get("/v1/clinic/pipeline"))).isEqualTo("CLINIC_NOT_SELECTED");
    assertThat(loose.put("/v1/auth/clinic/" + clinicA, true).statusCode()).isEqualTo(200);
    jdbc.update(
        "UPDATE tenant_memberships SET active = false WHERE tenant_id = ? AND subject_id = ?",
        clinicA,
        revoked);
    var refused = loose.get("/v1/clinic/pipeline");
    assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
    assertThat(code(refused)).isEqualTo("CLINIC_ACCESS_DENIED");
    // The session forgot the clinic: the next request asks for a selection.
    assertThat(code(loose.get("/v1/clinic/pipeline"))).isEqualTo("CLINIC_NOT_SELECTED");

    // A's owner cannot select B, and keeps reading A's funnel.
    String funnelA = json(owner.get("/v1/clinic/pipeline")).get("id").asText();
    var cross = owner.put("/v1/auth/clinic/" + clinicB, true);
    assertThat(cross.statusCode()).as(cross.body()).isEqualTo(403);
    var still = owner.get("/v1/clinic/pipeline");
    assertThat(json(still).get("id").asText()).isEqualTo(funnelA);
    assertThat(still.body()).doesNotContain(json(b.get("/v1/clinic/pipeline")).get("id").asText());

    assertThat(new Browser(port).get("/v1/clinic/pipeline").statusCode()).isEqualTo(401);
  }

  private static JsonNode json(HttpResponse<String> response) {
    return Browser.JSON.readTree(response.body());
  }

  private static String code(HttpResponse<String> response) {
    return json(response).get("code").asText();
  }

  private Browser selected(String email, UUID clinic) throws Exception {
    Browser browser = loggedIn(email);
    var selection = browser.put("/v1/auth/clinic/" + clinic, true);
    assertThat(selection.statusCode()).as(selection.body()).isEqualTo(200);
    return browser;
  }

  private Browser loggedIn(String email) throws Exception {
    Browser browser = new Browser(port);
    browser.csrf();
    assertThat(browser.login(email, LOGIN_PASSWORD).statusCode()).isEqualTo(204);
    browser.csrf();
    MfaFixture.complete(browser, jdbc);
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
      return Files.createTempDirectory("branly-pipeline-api-");
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
