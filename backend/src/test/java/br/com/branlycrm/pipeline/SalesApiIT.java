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
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
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
 * Sales API over real HTTP (CRM-035) on clinics created by the real provisioner: registration
 * closing the lead, patient flag, corrections and cancellation with history, roles, clinic
 * isolation and concurrent registrations. One test, so each browser logs in once.
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
class SalesApiIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final Path SECRETS = temporaryDirectory();
  private static final String LOGIN_PASSWORD = "Synthetic sales api secret 2026!";
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
  void saleFlowCorrectionsRolesIsolationAndConcurrency() throws Exception {
    UUID clinicA = provision(account("owner-a@example.test"));
    UUID clinicB = provision(account("owner-b@example.test"));
    member(clinicA, account("attendant-a@example.test"), "ATTENDANT");
    member(clinicA, account("viewer-a@example.test"), "VIEWER");
    Browser owner = selected("owner-a@example.test", clinicA);
    Browser attendant = selected("attendant-a@example.test", clinicA);
    Browser viewer = selected("viewer-a@example.test", clinicA);
    Browser ownerB = selected("owner-b@example.test", clinicB);

    String procedure = created(owner, "/v1/clinic/procedures", procedureBody("Limpeza"));
    String other = created(owner, "/v1/clinic/procedures", procedureBody("Clareamento"));
    String retired = created(owner, "/v1/clinic/procedures", procedureBody("Antigo"));
    String professional =
        created(owner, "/v1/clinic/professionals", "{\"name\":\"Dra. Sintética\"}");
    assertThat(
            owner
                .putJson("/v1/clinic/procedures/" + retired + "/active", "{\"active\":false}", true)
                .statusCode())
        .isEqualTo(200);
    String contact = contact(owner, "(11) 98300-0001");
    String lead = lead(owner, contact);

    // Refusals: no amount, no procedure, fractional cents, inactive item, viewer.
    String sale = LEADS + "/" + lead + "/sale";
    assertThat(
            fields(
                owner.postJson(
                    sale, "{\"procedureId\":\"" + procedure + "\",\"version\":0}", true)))
        .containsExactly("amountCents");
    assertThat(fields(owner.postJson(sale, "{\"amountCents\":1000,\"version\":0}", true)))
        .containsExactly("procedureId");
    assertThat(owner.postJson(sale, saleBody(procedure, "10.5", 0), true).statusCode())
        .isEqualTo(400);
    assertThat(code(owner.postJson(sale, saleBody(retired, "1000", 0), true)))
        .isEqualTo("SALE_PROCEDURE_UNAVAILABLE");
    assertThat(code(viewer.postJson(sale, saleBody(procedure, "1000", 0), true)))
        .isEqualTo("CLINIC_ACCESS_DENIED");
    assertThat(code(ownerB.postJson(sale, saleBody(procedure, "1000", 0), true)))
        .isEqualTo("LEAD_NOT_FOUND");

    // The attendant registers: the lead is won and the contact becomes a patient.
    var registered =
        attendant.postJson(
            sale,
            "{\"procedureId\":\""
                + procedure
                + "\",\"professionalId\":\""
                + professional
                + "\",\"amountCents\":25000,\"paymentMethod\":\"PIX\",\"version\":0}",
            true);
    assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
    JsonNode registeredSale = json(registered);
    String saleId = registeredSale.get("id").asText();
    assertThat(registeredSale.get("status").asText()).isEqualTo("ACTIVE");
    assertThat(registeredSale.get("soldOn").asText()).isNotEmpty();
    assertThat(json(owner.get(LEADS + "/" + lead)).get("status").asText()).isEqualTo("WON");
    assertThat(json(owner.get("/v1/clinic/contacts/" + contact)).get("patient").asBoolean())
        .isTrue();
    assertThat(code(owner.postJson(sale, saleBody(procedure, "1000", 1), true)))
        .isEqualTo("LEAD_CLOSED");

    // Corrections: manager-level only, with a reason, kept in the history.
    String sales = "/v1/clinic/sales/" + saleId;
    String correction =
        "{\"procedureId\":\""
            + other
            + "\",\"amountCents\":30000,\"soldOn\":\""
            + registeredSale.get("soldOn").asText()
            + "\",\"version\":0,\"reason\":\"Valor digitado errado\"}";
    assertThat(code(attendant.putJson(sales, correction, true))).isEqualTo("CLINIC_ACCESS_DENIED");
    assertThat(fields(owner.putJson(sales, correction.replace("Valor digitado errado", " "), true)))
        .containsExactly("reason");
    var corrected = owner.putJson(sales, correction, true);
    assertThat(corrected.statusCode()).as(corrected.body()).isEqualTo(200);
    assertThat(json(corrected).get("amountCents").asLong()).isEqualTo(30000);
    assertThat(json(corrected).get("professionalId").isNull()).isTrue();
    assertThat(code(owner.putJson(sales, correction, true))).isEqualTo("SALE_STALE");
    JsonNode history = json(viewer.get(sales + "/events"));
    assertThat(history).hasSize(2);
    assertThat(history.get(1).get("type").asText()).isEqualTo("CORRECTED");
    assertThat(history.get(1).get("previousAmountCents").asLong()).isEqualTo(25000);
    assertThat(history.get(1).get("amountCents").asLong()).isEqualTo(30000);

    // Clinic B neither reads nor changes A's sale.
    assertThat(code(ownerB.get(sales))).isEqualTo("SALE_NOT_FOUND");
    assertThat(code(ownerB.get(sales + "/events"))).isEqualTo("SALE_NOT_FOUND");
    assertThat(json(ownerB.get("/v1/clinic/sales")).get("total").asLong()).isZero();
    assertThat(
            code(ownerB.putJson(sales, correction.replace("\"version\":0", "\"version\":1"), true)))
        .isEqualTo("SALE_NOT_FOUND");
    String cancel = "{\"reason\":\"Paciente desistiu\",\"version\":2}";
    assertThat(code(ownerB.postJson(sales + "/cancellation", cancel, true)))
        .isEqualTo("SALE_NOT_FOUND");

    // The item already on the sale may be inactive; a new choice must be active; no future day.
    assertThat(
            owner
                .putJson("/v1/clinic/procedures/" + other + "/active", "{\"active\":false}", true)
                .statusCode())
        .isEqualTo(200);
    String keep = correction.replace("30000", "31000").replace("\"version\":0", "\"version\":1");
    var kept = owner.putJson(sales, keep, true);
    assertThat(kept.statusCode()).as(kept.body()).isEqualTo(200);
    String swap = keep.replace(other, retired).replace("\"version\":1", "\"version\":2");
    assertThat(code(owner.putJson(sales, swap, true))).isEqualTo("SALE_PROCEDURE_UNAVAILABLE");
    String future =
        keep.replace(registeredSale.get("soldOn").asText(), LocalDate.now().plusDays(2).toString())
            .replace("\"version\":1", "\"version\":2");
    assertThat(fields(owner.putJson(sales, future, true))).containsExactly("soldOn");
    assertThat(json(ownerB.get("/v1/clinic/sales?leadId=" + lead)).get("total").asLong()).isZero();

    // The runtime never deletes sales nor rewrites their lead, contact, authorship or events.
    String suffix = clinicA.toString().replace("-", "");
    String role = "tenant_" + suffix;
    try (Connection admin = TestPostgres.admin(POSTGRES, "clinic_" + suffix)) {
      List<String> checks = new ArrayList<>();
      for (String table : List.of("sales", "sale_events")) {
        checks.add("has_table_privilege('" + role + "', '" + table + "', 'DELETE,TRUNCATE')");
        checks.add("has_table_privilege('" + role + "', '" + table + "', 'UPDATE')");
      }
      for (String column : List.of("id", "lead_id", "contact_id", "created_at", "created_by")) {
        checks.add("has_column_privilege('" + role + "', 'sales', '" + column + "', 'UPDATE')");
      }
      checks.add("has_any_column_privilege('" + role + "', 'sale_events', 'UPDATE')");
      for (String check : checks) {
        try (var rows = admin.createStatement().executeQuery("SELECT " + check)) {
          rows.next();
          assertThat(rows.getBoolean(1)).as(check).isFalse();
        }
      }
    }

    // Listing and filters.
    JsonNode listed = json(viewer.get("/v1/clinic/sales?contactId=" + contact));
    assertThat(listed.get("total").asLong()).isOne();
    assertThat(json(owner.get("/v1/clinic/sales?status=CANCELLED")).get("total").asLong()).isZero();

    // Cancellation: the lead stays won, the contact stops being a patient, no more changes.
    assertThat(code(attendant.postJson(sales + "/cancellation", cancel, true)))
        .isEqualTo("CLINIC_ACCESS_DENIED");
    var cancelled = owner.postJson(sales + "/cancellation", cancel, true);
    assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
    assertThat(json(cancelled).get("status").asText()).isEqualTo("CANCELLED");
    assertThat(json(owner.get(LEADS + "/" + lead)).get("status").asText()).isEqualTo("WON");
    assertThat(json(owner.get("/v1/clinic/contacts/" + contact)).get("patient").asBoolean())
        .isFalse();
    assertThat(code(owner.postJson(sales + "/cancellation", cancel.replace(":2", ":3"), true)))
        .isEqualTo("SALE_CANCELLED");

    // Two registrations of one lead at the same version: exactly one sale.
    String racing = lead(owner, contact(owner, "(11) 98300-0002"));
    List<Integer> statuses = new ArrayList<>();
    var pool = Executors.newFixedThreadPool(2);
    try {
      var barrier = new CyclicBarrier(2);
      List<Future<Integer>> results = new ArrayList<>();
      for (Browser browser : List.of(owner, attendant)) {
        results.add(
            pool.submit(
                () -> {
                  barrier.await(10, TimeUnit.SECONDS);
                  return browser
                      .postJson(
                          LEADS + "/" + racing + "/sale", saleBody(procedure, "5000", 0), true)
                      .statusCode();
                }));
      }
      for (Future<Integer> result : results) statuses.add(result.get(30, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
    assertThat(statuses).containsExactlyInAnyOrder(201, 409);
    assertThat(json(owner.get("/v1/clinic/sales?leadId=" + racing)).get("total").asLong()).isOne();
  }

  private static String procedureBody(String name) {
    return "{\"name\":\"" + name + "\",\"referencePriceCents\":10000}";
  }

  private static String saleBody(String procedureId, String amount, long version) {
    return "{\"procedureId\":\""
        + procedureId
        + "\",\"amountCents\":"
        + amount
        + ",\"version\":"
        + version
        + "}";
  }

  private static String created(Browser browser, String path, String body) throws Exception {
    var created = browser.postJson(path, body, true);
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    return json(created).get("id").asText();
  }

  private static String lead(Browser browser, String contactId) throws Exception {
    return created(browser, LEADS, "{\"contactId\":\"" + contactId + "\"}");
  }

  private static List<String> fields(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
    List<String> names = new ArrayList<>();
    json(response).get("fields").forEach(field -> names.add(field.asText()));
    return names;
  }

  private String contact(Browser browser, String phone) throws Exception {
    var created =
        browser.postJson(
            "/v1/clinic/contacts",
            "{\"name\":\"Paciente Sintético\",\"phone\":\"" + phone + "\"}",
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
      return Files.createTempDirectory("branly-sales-api-");
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
