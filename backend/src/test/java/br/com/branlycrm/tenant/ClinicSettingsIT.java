package br.com.branlycrm.tenant;

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
 * Clinic name and time zone over real HTTP (CRM-013): the owner changes both, every other role only
 * reads them, invalid input is refused, the new zone reaches the dashboard and another clinic is
 * never affected. One test, so each browser logs in once.
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
class ClinicSettingsIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final Path SECRETS = temporaryDirectory();
  private static final String LOGIN_PASSWORD = "Synthetic clinic settings secret 2026!";
  private static final String SETTINGS = "/v1/clinic/settings";
  private static final String NAME = SETTINGS + "/name";
  private static final String ZONE = SETTINGS + "/time-zone";

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
  void ownerChangesNameAndZoneOthersOnlyReadAndOtherClinicsStayUntouched() throws Exception {
    UUID clinicA = provision(account("owner-a@example.test"));
    UUID clinicB = provision(account("owner-b@example.test"));
    member(clinicA, account("manager-a@example.test"), "MANAGER");
    member(clinicA, account("attendant-a@example.test"), "ATTENDANT");
    member(clinicA, account("viewer-a@example.test"), "VIEWER");
    Browser owner = selected("owner-a@example.test", clinicA);
    Browser ownerB = selected("owner-b@example.test", clinicB);

    JsonNode initial = ok(owner.get(SETTINGS));
    assertThat(initial.get("clinicName").asText()).isEqualTo("Clínica sintética");
    assertThat(initial.get("timeZone").asText()).isEqualTo("America/Sao_Paulo");

    // Owner renames (trimmed) and the session's clinic list shows the new name.
    JsonNode renamed = ok(owner.putJson(NAME, "{\"clinicName\":\"  Clínica Aurora  \"}", true));
    assertThat(renamed.get("clinicName").asText()).isEqualTo("Clínica Aurora");
    assertThat(ok(owner.get("/v1/auth/clinics")).get(0).get("clinicName").asText())
        .isEqualTo("Clínica Aurora");

    // Owner changes the zone and the dashboard follows it.
    JsonNode zoned = ok(owner.putJson(ZONE, "{\"timeZone\":\"America/Manaus\"}", true));
    assertThat(zoned.get("timeZone").asText()).isEqualTo("America/Manaus");
    assertThat(zoned.get("clinicName").asText()).isEqualTo("Clínica Aurora");
    assertThat(ok(owner.get("/v1/clinic/dashboard")).get("timeZone").asText())
        .isEqualTo("America/Manaus");

    // Invalid input names only the field and changes nothing.
    for (String body :
        List.of(
            "{\"clinicName\":\"   \"}",
            "{\"clinicName\":null}",
            "{\"clinicName\":\"" + "a".repeat(161) + "\"}",
            "{\"clinicName\":\"Cl\\u0007nica\"}",
            "{\"clinicName\":\"Cl\\u202Enica\"}")) {
      invalid(owner.putJson(NAME, body, true), "clinicName");
    }
    for (String zone : List.of("UTC", "+03:00", "Etc/GMT+3", "America/Atlantida", "")) {
      invalid(owner.putJson(ZONE, "{\"timeZone\":\"" + zone + "\"}", true), "timeZone");
    }
    invalid(owner.putJson(ZONE, "not json", true), "body");
    invalid(owner.putJson(NAME, "not json", true), "body");
    JsonNode unchanged = ok(owner.get(SETTINGS));
    assertThat(unchanged.get("clinicName").asText()).isEqualTo("Clínica Aurora");
    assertThat(unchanged.get("timeZone").asText()).isEqualTo("America/Manaus");

    // Manager, attendant and viewer read but never change.
    for (String email :
        List.of("manager-a@example.test", "attendant-a@example.test", "viewer-a@example.test")) {
      Browser other = selected(email, clinicA);
      assertThat(ok(other.get(SETTINGS)).get("clinicName").asText()).isEqualTo("Clínica Aurora");
      denied(other.putJson(NAME, "{\"clinicName\":\"Outra\"}", true));
      denied(other.putJson(ZONE, "{\"timeZone\":\"America/Recife\"}", true));
    }

    // Clinic B keeps its own settings and its owner changes only B.
    JsonNode b = ok(ownerB.get(SETTINGS));
    assertThat(b.get("clinicName").asText()).isEqualTo("Clínica sintética");
    assertThat(b.get("timeZone").asText()).isEqualTo("America/Sao_Paulo");
    ok(ownerB.putJson(NAME, "{\"clinicName\":\"Clínica Boreal\"}", true));
    assertThat(ok(owner.get(SETTINGS)).get("clinicName").asText()).isEqualTo("Clínica Aurora");

    // Logged in without a selected clinic: nothing to read or change.
    Browser unselected = new Browser(port);
    unselected.csrf();
    assertThat(unselected.login("owner-a@example.test", LOGIN_PASSWORD).statusCode())
        .isEqualTo(204);
    unselected.csrf();
    MfaFixture.complete(unselected, jdbc);
    for (HttpResponse<String> response :
        List.of(
            unselected.get(SETTINGS),
            unselected.putJson(NAME, "{\"clinicName\":\"Sem seleção\"}", true))) {
      assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
      assertThat(json(response).get("code").asText()).isEqualTo("CLINIC_NOT_SELECTED");
    }

    // An owner demoted after selecting the clinic can no longer rename it.
    member(clinicB, account("owner-b2@example.test"), "OWNER");
    jdbc.update(
        "UPDATE tenant_memberships SET role = 'MANAGER' WHERE tenant_id = ? AND subject_id ="
            + " (SELECT subject_id FROM user_accounts WHERE email = 'owner-b@example.test')",
        clinicB);
    denied(ownerB.putJson(NAME, "{\"clinicName\":\"Rebaixado\"}", true));
    assertThat(clinicName(clinicB)).isEqualTo("Clínica Boreal");

    // Without CSRF the change is refused before reaching the controller.
    assertThat(owner.putJson(NAME, "{\"clinicName\":\"Sem CSRF\"}", false).statusCode())
        .isEqualTo(403);
    assertThat(clinicName(clinicA)).isEqualTo("Clínica Aurora");
  }

  private static JsonNode ok(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return json(response);
  }

  private static void invalid(HttpResponse<String> response, String field) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
    JsonNode body = json(response);
    assertThat(body.get("code").asText()).isEqualTo("INVALID_INPUT");
    assertThat(body.get("fields").get(0).asText()).isEqualTo(field);
  }

  private static void denied(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
    assertThat(json(response).get("code").asText()).isEqualTo("CLINIC_ACCESS_DENIED");
  }

  private static JsonNode json(HttpResponse<String> response) {
    return Browser.JSON.readTree(response.body());
  }

  private String clinicName(UUID tenant) {
    return jdbc.queryForObject(
        "SELECT clinic_name FROM tenants WHERE tenant_id = ?", String.class, tenant);
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
      return Files.createTempDirectory("branly-clinic-settings-");
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
