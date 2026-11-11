package br.com.branlycrm.report;

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
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Monthly report and reception dashboard over real HTTP (CRM-051): a scenario with known values
 * built through the API, checked against docs/metricas.md, plus roles, month validation and clinic
 * isolation. One test, so each browser logs in once.
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
class ReportApiIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final Path SECRETS = temporaryDirectory();
  private static final String LOGIN_PASSWORD = "Synthetic report api secret 2026!";
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
  void monthlyReportAndDashboardMatchTheMetricDefinitions() throws Exception {
    ZoneId zone = ZoneId.of("America/Sao_Paulo");
    Instant midnight = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant();
    if (Duration.between(Instant.now(), midnight).compareTo(Duration.ofMinutes(10)) < 0) {
      // Too close to the clinic's midnight (and maybe the month's end): wait for the new day.
      Thread.sleep(Duration.between(Instant.now(), midnight).plusSeconds(5).toMillis());
      midnight = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant();
    }
    YearMonth month = YearMonth.now(zone);
    LocalDate previousDay = month.minusMonths(1).atEndOfMonth();

    UUID ownerA = account("owner-a@example.test");
    UUID clinicA = provision(ownerA);
    UUID clinicB = provision(account("owner-b@example.test"));
    member(clinicA, account("attendant-a@example.test"), "ATTENDANT");
    member(clinicA, account("viewer-a@example.test"), "VIEWER");
    Browser owner = selected("owner-a@example.test", clinicA);
    Browser attendant = selected("attendant-a@example.test", clinicA);
    Browser viewer = selected("viewer-a@example.test", clinicA);
    Browser ownerB = selected("owner-b@example.test", clinicB);

    String cleaning = created(owner, "/v1/clinic/procedures", procedure("Limpeza"));
    String whitening = created(owner, "/v1/clinic/procedures", procedure("Clareamento"));
    String reason = json(owner.get("/v1/clinic/loss-reasons")).get(0).get("id").asText();

    // Cold lead won: counts as sale, cold reverted and revenue of the month.
    String cold = lead(owner, contact(owner, "(11) 98700-0001"), ",\"temperature\":\"COLD\"");
    sale(owner, cold, cleaning, 10000, null);
    // Hot lead won, sale cancelled later: the lead stays won but counts nowhere.
    String cancelled = lead(owner, contact(owner, "(11) 98700-0002"), ",\"temperature\":\"HOT\"");
    String cancelledSale = sale(owner, cancelled, whitening, 30000, null);
    assertThat(
            owner
                .postJson(
                    "/v1/clinic/sales/" + cancelledSale + "/cancellation",
                    "{\"reason\":\"Desistiu\",\"version\":0}",
                    true)
                .statusCode())
        .isEqualTo(200);
    // Won this month with a sale day in the previous month: closed now, revenue then.
    String backdated = lead(owner, contact(owner, "(11) 98700-0003"), "");
    sale(owner, backdated, cleaning, 5000, previousDay.toString());
    // Lost with a reason archived afterwards: still in the report.
    String lost = lead(owner, contact(owner, "(11) 98700-0004"), "");
    assertThat(
            owner
                .postJson(
                    LEADS + "/" + lost + "/loss",
                    "{\"lossReasonId\":\"" + reason + "\",\"version\":0}",
                    true)
                .statusCode())
        .isEqualTo(200);
    assertThat(
            owner
                .putJson(
                    "/v1/clinic/loss-reasons/" + reason + "/archived", "{\"archived\":true}", true)
                .statusCode())
        .isEqualTo(200);
    // Still open, referred and warm.
    lead(
        owner,
        contact(owner, "(11) 98700-0005"),
        ",\"temperature\":\"WARM\",\"origin\":{\"type\":\"REFERRAL\"}");
    // Tasks for the dashboard: one overdue, one due later today.
    String someone = contact(owner, "(11) 98700-0006");
    task(owner, someone, ownerA, Instant.now().minus(Duration.ofHours(1)));
    task(
        owner,
        someone,
        ownerA,
        Instant.now().plus(Duration.between(Instant.now(), midnight).dividedBy(2)));

    // Clinic B's activity never reaches A.
    String other = lead(ownerB, contact(ownerB, "(11) 98700-0001"), "");
    sale(ownerB, other, created(ownerB, "/v1/clinic/procedures", procedure("Outro")), 99900, null);

    JsonNode report = ok(viewer.get(MONTHLY));
    assertThat(report.get("month").asText()).isEqualTo(month.toString());
    assertThat(report.get("timeZone").asText()).isEqualTo("America/Sao_Paulo");
    assertThat(report.get("contactsCreated").asLong()).isEqualTo(6);
    assertThat(counts(report.get("leadsByOrigin")))
        .containsEntry("UNIDENTIFIED", 4L)
        .containsEntry("REFERRAL", 1L)
        .containsEntry("PAID_TRAFFIC", 0L);
    assertThat(counts(report.get("leadsByTemperature")))
        .containsEntry("COLD", 1L)
        .containsEntry("HOT", 1L)
        .containsEntry("WARM", 1L)
        .containsEntry("NONE", 2L)
        .containsEntry("QUALIFIED", 0L);
    assertThat(report.get("salesClosed").asLong()).isEqualTo(2);
    assertThat(report.get("lost").asLong()).isEqualTo(1);
    assertThat(report.get("conversionPercent").asDouble()).isEqualTo(66.7);
    assertThat(report.get("lostByReason")).hasSize(1);
    assertThat(report.get("lostByReason").get(0).get("id").asText()).isEqualTo(reason);
    assertThat(report.get("coldReverted").asLong()).isEqualTo(1);
    assertThat(report.get("salesCount").asLong()).isEqualTo(1);
    assertThat(report.get("revenueCents").asLong()).isEqualTo(10000);
    assertThat(report.get("topProcedures")).hasSize(1);
    assertThat(report.get("topProcedures").get(0).get("id").asText()).isEqualTo(cleaning);

    JsonNode previous = ok(owner.get(MONTHLY + "?month=" + month.minusMonths(1)));
    assertThat(previous.get("salesCount").asLong()).isEqualTo(1);
    assertThat(previous.get("revenueCents").asLong()).isEqualTo(5000);
    assertThat(previous.get("salesClosed").asLong()).isZero();
    assertThat(previous.get("conversionPercent").isNull()).isTrue();
    assertThat(previous.get("contactsCreated").asLong()).isZero();

    JsonNode dashboard = ok(attendant.get("/v1/clinic/dashboard"));
    assertThat(dashboard.get("leadsToday").asLong()).isEqualTo(5);
    assertThat(dashboard.get("salesTodayCount").asLong()).isEqualTo(1);
    assertThat(dashboard.get("salesTodayCents").asLong()).isEqualTo(10000);
    assertThat(dashboard.get("salesMonthCount").asLong()).isEqualTo(1);
    assertThat(dashboard.get("tasksOverdue").asLong()).isEqualTo(1);
    assertThat(dashboard.get("tasksToday").asLong()).isEqualTo(1);

    // Clinic B sees only its own numbers.
    JsonNode reportB = ok(ownerB.get(MONTHLY));
    assertThat(reportB.get("contactsCreated").asLong()).isEqualTo(1);
    assertThat(reportB.get("revenueCents").asLong()).isEqualTo(99900);

    // The attendant sees the dashboard, not the monthly report; months are validated.
    assertThat(code(attendant.get(MONTHLY))).isEqualTo("CLINIC_ACCESS_DENIED");
    for (String invalid : List.of("abc", "1999-12", month.plusMonths(1).toString())) {
      assertThat(owner.get(MONTHLY + "?month=" + invalid).statusCode()).as(invalid).isEqualTo(400);
    }
  }

  private static final String MONTHLY = "/v1/clinic/reports/monthly";

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

  private static void task(Browser browser, String contactId, UUID responsible, Instant due)
      throws Exception {
    created(
        browser,
        "/v1/clinic/tasks",
        "{\"title\":\"Retorno\",\"contactId\":\""
            + contactId
            + "\",\"responsibleId\":\""
            + responsible
            + "\",\"dueAt\":\""
            + due
            + "\"}");
  }

  private static JsonNode ok(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return json(response);
  }

  private static Map<String, Long> counts(JsonNode buckets) {
    Map<String, Long> counts = new HashMap<>();
    buckets.forEach(bucket -> counts.put(bucket.get("key").asText(), bucket.get("count").asLong()));
    return counts;
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
      return Files.createTempDirectory("branly-report-api-");
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
