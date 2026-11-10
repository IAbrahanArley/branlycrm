package br.com.branlycrm.catalog;

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
 * Catalog API over real HTTP (CRM-034) on clinics created by the real provisioner: procedures and
 * professionals, the active filter, versions, duplicates, roles and clinic isolation. One test, so
 * each browser logs in once (the login limit is per minute).
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
class CatalogApiIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final Path SECRETS = temporaryDirectory();
  private static final String LOGIN_PASSWORD = "Synthetic catalog api secret 2026!";
  private static final String PROCEDURES = "/v1/clinic/procedures";
  private static final String PROFESSIONALS = "/v1/clinic/professionals";

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
  void catalogRolesVersionsAndIsolation() throws Exception {
    UUID clinicA = provision(account("owner-a@example.test"));
    UUID clinicB = provision(account("owner-b@example.test"));
    member(clinicA, account("attendant-a@example.test"), "ATTENDANT");
    member(clinicA, account("viewer-a@example.test"), "VIEWER");
    Browser owner = selected("owner-a@example.test", clinicA);
    Browser attendant = selected("attendant-a@example.test", clinicA);
    Browser viewer = selected("viewer-a@example.test", clinicA);
    Browser ownerB = selected("owner-b@example.test", clinicB);

    // Procedures: create, edit at the version read, duplicates whatever the case.
    var botox =
        owner.postJson(
            PROCEDURES,
            "{\"name\":\"Toxina botulínica\",\"referencePriceCents\":150000,"
                + "\"category\":\"Estética\",\"returnIntervalDays\":120}",
            true);
    assertThat(botox.statusCode()).as(botox.body()).isEqualTo(201);
    String procedure = json(botox).get("id").asText();
    assertThat(json(botox).get("referencePriceCents").asLong()).isEqualTo(150000);
    assertThat(json(botox).get("active").asBoolean()).isTrue();
    assertThat(
            code(
                owner.postJson(
                    PROCEDURES,
                    "{\"name\":\"TOXINA BOTULÍNICA\",\"referencePriceCents\":1}",
                    true)))
        .isEqualTo("PROCEDURE_DUPLICATE");
    var negative =
        owner.postJson(PROCEDURES, "{\"name\":\"Limpeza\",\"referencePriceCents\":-1}", true);
    assertThat(negative.statusCode()).isEqualTo(400);
    var fractional =
        owner.postJson(PROCEDURES, "{\"name\":\"Limpeza\",\"referencePriceCents\":1.5}", true);
    assertThat(fractional.statusCode()).as(fractional.body()).isEqualTo(400);
    var edited =
        owner.putJson(
            PROCEDURES + "/" + procedure,
            "{\"name\":\"Toxina\",\"referencePriceCents\":160000,\"version\":0}",
            true);
    assertThat(edited.statusCode()).as(edited.body()).isEqualTo(200);
    assertThat(json(edited).get("version").asLong()).isOne();
    assertThat(json(edited).get("category").isNull()).isTrue();
    var stale =
        owner.putJson(
            PROCEDURES + "/" + procedure,
            "{\"name\":\"Toxina 2\",\"referencePriceCents\":1,\"version\":0}",
            true);
    assertThat(code(stale)).isEqualTo("PROCEDURE_STALE");

    // Inactive ones leave the default list and stay with includeInactive; idempotent.
    String deactivate = "{\"active\":false}";
    var off = owner.putJson(PROCEDURES + "/" + procedure + "/active", deactivate, true);
    assertThat(off.statusCode()).as(off.body()).isEqualTo(200);
    var offAgain = owner.putJson(PROCEDURES + "/" + procedure + "/active", deactivate, true);
    assertThat(offAgain.statusCode()).isEqualTo(200);
    assertThat(json(offAgain).get("version").asLong()).isEqualTo(2);
    assertThat(json(owner.get(PROCEDURES))).isEmpty();
    assertThat(json(viewer.get(PROCEDURES + "?includeInactive=true"))).hasSize(1);
    assertThat(owner.get(PROCEDURES + "?includeInactive=yes").statusCode()).isEqualTo(400);

    // Professionals.
    var doctor = owner.postJson(PROFESSIONALS, "{\"name\":\"Dra. Sintética\"}", true);
    assertThat(doctor.statusCode()).as(doctor.body()).isEqualTo(201);
    String professional = json(doctor).get("id").asText();
    assertThat(code(owner.postJson(PROFESSIONALS, "{\"name\":\"dra. sintética\"}", true)))
        .isEqualTo("PROFESSIONAL_DUPLICATE");
    var renamed =
        owner.putJson(
            PROFESSIONALS + "/" + professional, "{\"name\":\"Dr. Fictício\",\"version\":0}", true);
    assertThat(renamed.statusCode()).as(renamed.body()).isEqualTo(200);
    assertThat(json(attendant.get(PROFESSIONALS)).get(0).get("name").asText())
        .isEqualTo("Dr. Fictício");

    // Only owner and manager change the catalog; everyone reads it.
    assertThat(attendant.get(PROCEDURES).statusCode()).isEqualTo(200);
    for (Browser denied : new Browser[] {attendant, viewer}) {
      var create =
          denied.postJson(PROCEDURES, "{\"name\":\"Outro\",\"referencePriceCents\":1}", true);
      assertThat(code(create)).isEqualTo("CLINIC_ACCESS_DENIED");
      var activate =
          denied.putJson(PROCEDURES + "/" + procedure + "/active", "{\"active\":true}", true);
      assertThat(code(activate)).isEqualTo("CLINIC_ACCESS_DENIED");
      var add = denied.postJson(PROFESSIONALS, "{\"name\":\"Outro\"}", true);
      assertThat(code(add)).isEqualTo("CLINIC_ACCESS_DENIED");
      var rename =
          denied.putJson(
              PROFESSIONALS + "/" + professional, "{\"name\":\"Outro\",\"version\":1}", true);
      assertThat(code(rename)).isEqualTo("CLINIC_ACCESS_DENIED");
    }

    // Clinic B neither lists nor changes A's catalog, and the same names are free there.
    assertThat(json(ownerB.get(PROCEDURES + "?includeInactive=true"))).isEmpty();
    assertThat(json(ownerB.get(PROFESSIONALS + "?includeInactive=true"))).isEmpty();
    var editB =
        ownerB.putJson(
            PROCEDURES + "/" + procedure,
            "{\"name\":\"Invasão\",\"referencePriceCents\":1,\"version\":2}",
            true);
    assertThat(code(editB)).isEqualTo("PROCEDURE_NOT_FOUND");
    var activateB =
        ownerB.putJson(PROCEDURES + "/" + procedure + "/active", "{\"active\":true}", true);
    assertThat(code(activateB)).isEqualTo("PROCEDURE_NOT_FOUND");
    var renameB =
        ownerB.putJson(
            PROFESSIONALS + "/" + professional, "{\"name\":\"Invasão\",\"version\":1}", true);
    assertThat(code(renameB)).isEqualTo("PROFESSIONAL_NOT_FOUND");
    var deactivateB =
        ownerB.putJson(PROFESSIONALS + "/" + professional + "/active", deactivate, true);
    assertThat(code(deactivateB)).isEqualTo("PROFESSIONAL_NOT_FOUND");
    var sameName = ownerB.postJson(PROFESSIONALS, "{\"name\":\"Dr. Fictício\"}", true);
    assertThat(sameName.statusCode()).as(sameName.body()).isEqualTo(201);

    JsonNode procedures = json(owner.get(PROCEDURES + "?includeInactive=true"));
    assertThat(procedures.get(0).get("name").asText()).isEqualTo("Toxina");

    // The runtime never deletes and never rewrites ids or authorship.
    String suffix = clinicA.toString().replace("-", "");
    String role = "tenant_" + suffix;
    try (Connection admin = TestPostgres.admin(POSTGRES, "clinic_" + suffix)) {
      for (String table : List.of("procedures", "professionals")) {
        for (String check :
            List.of(
                "has_table_privilege('" + role + "', '" + table + "', 'DELETE,TRUNCATE')",
                "has_table_privilege('" + role + "', '" + table + "', 'UPDATE')",
                "has_column_privilege('" + role + "', '" + table + "', 'id', 'UPDATE')",
                "has_column_privilege('" + role + "', '" + table + "', 'created_by', 'UPDATE')")) {
          try (var rows = admin.createStatement().executeQuery("SELECT " + check)) {
            rows.next();
            assertThat(rows.getBoolean(1)).as(check).isFalse();
          }
        }
      }
    }
    assertThat(procedures.get(0).get("active").asBoolean()).isFalse();
    assertThat(json(owner.get(PROFESSIONALS)).get(0).get("active").asBoolean()).isTrue();
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
      return Files.createTempDirectory("branly-catalog-api-");
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
