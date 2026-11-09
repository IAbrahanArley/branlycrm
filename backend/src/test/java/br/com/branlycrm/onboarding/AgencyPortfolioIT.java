package br.com.branlycrm.onboarding;

import static br.com.branlycrm.onboarding.InvitationFixtures.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.identity.mail.PublicOrigin;
import br.com.branlycrm.platform.repositories.PlatformRepository;
import br.com.branlycrm.platform.services.PlatformCommand;
import br.com.branlycrm.support.Browser;
import br.com.branlycrm.support.MfaFixture;
import br.com.branlycrm.support.TestPostgres;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * The agency portfolio over real HTTP, with the API as crm_control_runtime and the platform command
 * as crm_platform_operator (ADR-026, ADR-028): platform creates the agency, the admin accepts, the
 * agency creates a clinic and its owner accepts, and the agency never gets clinic access. Also
 * isolation between agencies, idempotency, reissue, the unfinished-clinic cap and suspension.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.docker.compose.enabled=false",
      "app.mail.provider=local",
      "app.agency.max-unfinished-clinics=3",
      MfaFixture.KEY_PROPERTY
    })
class AgencyPortfolioIT {
  @Container static final PostgreSQLContainer POSTGRES = TestPostgres.container();
  static final Path MAIL = temporaryDirectory();
  private static final Pattern LINK = Pattern.compile("#token=([A-Za-z0-9_-]{43})");

  @LocalServerPort int port;
  @Autowired PasswordEncoder encoder;

  InvitationFixtures fixtures;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    String url = TestPostgres.url(POSTGRES, POSTGRES.getDatabaseName());
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.username", () -> TestPostgres.RUNTIME);
    registry.add("spring.datasource.password", () -> TestPostgres.PASSWORD);
    registry.add("spring.flyway.url", () -> url);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
    registry.add("app.mail.local-directory", MAIL::toString);
  }

  @BeforeAll
  static void deploymentRoles() throws SQLException {
    TestPostgres.bootstrapRoles(POSTGRES);
  }

  @BeforeEach
  void resetDisposableFixtures() {
    fixtures =
        new InvitationFixtures(
            new JdbcTemplate(
                new DriverManagerDataSource(
                    TestPostgres.url(POSTGRES, POSTGRES.getDatabaseName()),
                    POSTGRES.getUsername(),
                    POSTGRES.getPassword())),
            encoder,
            MAIL);
    fixtures.reset();
  }

  @Test
  void platformAgencyAndOwnerOnboardingNeverGivesTheAgencyClinicAccess() throws Exception {
    String output =
        platform(
            "create-agency",
            "--request-id",
            UUID.randomUUID().toString(),
            "--name",
            "Agência Sintética",
            "--admin-email",
            "admin@example.test");
    String adminToken = token(output);
    UUID agency = UUID.fromString(line(output, "agencyId: "));
    assertThat(code(accept(anonymous(), adminToken), 204)).isNull();

    Browser admin = loggedIn("admin@example.test");
    JsonNode agencies = json(admin.get("/v1/agencies"));
    assertThat(agencies.size()).isOne();
    assertThat(agencies.get(0).get("agencyId").asText()).isEqualTo(agency.toString());

    String request = UUID.randomUUID().toString();
    var created =
        createClinic(admin, agency, request, "Clínica da Carteira", "gestor@example.test");
    assertThat(created.statusCode()).isEqualTo(201);
    JsonNode clinic = json(created);
    assertThat(clinic.get("status").asText()).isEqualTo("PROVISIONING");
    assertThat(clinic.get("delivery").asText()).isEqualTo("SENT");
    UUID tenant = UUID.fromString(clinic.get("tenantId").asText());
    assertThat(
            fixtures
                .jdbc()
                .queryForObject(
                    "SELECT admin_subject_id IS NULL FROM tenant_provisioning_tasks"
                        + " WHERE tenant_id = ?",
                    Boolean.class,
                    tenant))
        .isTrue();
    long mails = fixtures.mailCount();
    String ownerToken = fixtures.lastMailedToken();

    var replay = createClinic(admin, agency, request, "Clínica da Carteira", "gestor@example.test");
    assertThat(replay.statusCode()).isEqualTo(200);
    assertThat(json(replay).get("tenantId").asText()).isEqualTo(tenant.toString());
    assertThat(fixtures.mailCount()).as("a replay sends nothing").isEqualTo(mails);
    var reused = createClinic(admin, agency, request, "Outro nome", "gestor@example.test");
    assertThat(code(reused, 409)).isEqualTo("IDEMPOTENCY_KEY_REUSED");

    JsonNode portfolio = json(admin.get("/v1/agencies/" + agency + "/clinics")).get("items");
    assertThat(portfolio.size()).isOne();
    assertThat(portfolio.get(0).get("ownerInvitation").asText()).isEqualTo("PENDING");
    assertThat(portfolio.toString()).doesNotContain("gestor@example.test");

    assertThat(code(accept(anonymous(), ownerToken), 204)).isNull();
    assertThat(fixtures.role(tenant, fixtures.subject("gestor@example.test"))).isEqualTo("OWNER");
    assertThat(fixtures.status(tenant))
        .as("the provisioner activates it later")
        .isEqualTo("PROVISIONING");
    assertThat(
            json(admin.get("/v1/agencies/" + agency + "/clinics"))
                .get("items")
                .get(0)
                .get("ownerInvitation")
                .asText())
        .isEqualTo("ACCEPTED");
    var afterOwner = reissue(admin, agency, tenant, "outro@example.test");
    assertThat(code(afterOwner, 409)).isEqualTo("AGENCY_CLINIC_HAS_OWNER");

    // The agency admin has no clinic membership and cannot select the clinic.
    assertThat(json(admin.get("/v1/auth/clinics")).size()).isZero();
    assertThat(admin.put("/v1/auth/clinic/" + tenant, true).statusCode()).isEqualTo(403);
    assertThat(fixtures.role(tenant, fixtures.subject("admin@example.test"))).isNull();
  }

  @Test
  void agenciesNeverReachEachOthersPortfolioNorDirectClinics() throws Exception {
    UUID agencyA = fixtures.agency("ACTIVE");
    UUID agencyB = fixtures.agency("ACTIVE");
    fixtures.agencyAdmin(agencyA, fixtures.account("admin-a@example.test", true, true));
    fixtures.agencyAdmin(agencyB, fixtures.account("admin-b@example.test", true, true));
    UUID clinicB = fixtures.clinic("Clínica da B", "AWAITING_OWNER", agencyB);
    UUID direct = fixtures.clinic("Clínica direta", "AWAITING_OWNER", null);
    UUID ownOf = fixtures.clinic("Clínica da A", "AWAITING_OWNER", agencyA);

    Browser admin = loggedIn("admin-a@example.test");
    var foreign = admin.get("/v1/agencies/" + agencyB + "/clinics");
    var missing = admin.get("/v1/agencies/" + UUID.randomUUID() + "/clinics");
    assertThat(foreign.statusCode()).isEqualTo(404);
    assertThat(foreign.body()).isEqualTo(missing.body());
    assertThat(code(foreign, 404)).isEqualTo("AGENCY_NOT_FOUND");
    var foreignCreate =
        createClinic(admin, agencyB, UUID.randomUUID().toString(), "Intrusa", "x@example.test");
    assertThat(code(foreignCreate, 404)).isEqualTo("AGENCY_NOT_FOUND");
    assertThat(
            fixtures
                .jdbc()
                .queryForObject(
                    "SELECT count(*) FROM tenants WHERE agency_id = ?", Integer.class, agencyB))
        .isOne();

    var ofOther = reissue(admin, agencyA, clinicB, "gestor@example.test");
    var ofDirect = reissue(admin, agencyA, direct, "gestor@example.test");
    var ofNobody = reissue(admin, agencyA, UUID.randomUUID(), "gestor@example.test");
    assertThat(code(ofOther, 404)).isEqualTo("CLINIC_NOT_FOUND");
    assertThat(ofOther.body()).isEqualTo(ofDirect.body()).isEqualTo(ofNobody.body());
    assertThat(fixtures.jdbc().queryForObject("SELECT count(*) FROM invitations", Integer.class))
        .as("nothing was issued for foreign clinics")
        .isZero();

    JsonNode portfolio = json(admin.get("/v1/agencies/" + agencyA + "/clinics")).get("items");
    assertThat(portfolio.size()).isOne();
    assertThat(portfolio.get(0).get("tenantId").asText()).isEqualTo(ownOf.toString());
  }

  @Test
  void ownerInvitationReissueRotatesTheTokenOrReplacesTheAddress() throws Exception {
    UUID agency = fixtures.agency("ACTIVE");
    fixtures.agencyAdmin(agency, fixtures.account("admin@example.test", true, true));
    Browser admin = loggedIn("admin@example.test");
    JsonNode clinic =
        json(
            createClinic(
                admin,
                agency,
                UUID.randomUUID().toString(),
                "Clínica reemitida",
                "um@example.test"));
    UUID tenant = UUID.fromString(clinic.get("tenantId").asText());
    String first = fixtures.lastMailedToken();

    var tooSoon = reissue(admin, agency, tenant, "um@example.test");
    assertThat(code(tooSoon, 429)).isEqualTo("INVITATION_SEND_LIMIT");
    fixtures
        .jdbc()
        .update(
            "UPDATE invitations SET last_sent_at = now() - interval '2 minutes'"
                + " WHERE tenant_id = ?",
            tenant);
    assertThat(reissue(admin, agency, tenant, "um@example.test").statusCode()).isEqualTo(200);
    String second = fixtures.lastMailedToken();
    assertThat(second).isNotEqualTo(first);
    assertThat(preview(first).statusCode()).as("the old link stops working").isEqualTo(404);

    var otherTooSoon = reissue(admin, agency, tenant, "dois@example.test");
    assertThat(code(otherTooSoon, 429))
        .as("the interval counts per clinic, whatever the address")
        .isEqualTo("INVITATION_SEND_LIMIT");
    backdateSends(tenant);
    assertThat(reissue(admin, agency, tenant, "dois@example.test").statusCode()).isEqualTo(200);
    String third = fixtures.lastMailedToken();
    assertThat(preview(second).statusCode()).isEqualTo(404);
    assertThat(preview(third).statusCode()).isEqualTo(200);
    assertThat(
            fixtures
                .jdbc()
                .queryForObject(
                    "SELECT count(*) FROM invitations WHERE tenant_id = ?"
                        + " AND accepted_at IS NULL AND revoked_at IS NULL",
                    Integer.class,
                    tenant))
        .isOne();
  }

  @Test
  void concurrentCreatesWithTheSameRequestCreateOneClinic() throws Exception {
    UUID agency = fixtures.agency("ACTIVE");
    fixtures.agencyAdmin(agency, fixtures.account("admin@example.test", true, true));
    Browser admin = loggedIn("admin@example.test");
    String request = UUID.randomUUID().toString();
    int racers = 4;
    var barrier = new CyclicBarrier(racers);
    List<Future<Integer>> answers = new ArrayList<>();
    try (var pool = Executors.newFixedThreadPool(racers)) {
      for (int i = 0; i < racers; i++) {
        answers.add(
            pool.submit(
                () -> {
                  barrier.await();
                  return createClinic(
                          admin, agency, request, "Clínica concorrente", "gestor@example.test")
                      .statusCode();
                }));
      }
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> answer : answers) statuses.add(answer.get());
      assertThat(statuses).containsOnly(201, 200);
      assertThat(statuses.stream().filter(status -> status == 201).count()).isOne();
    }
    for (String table : new String[] {"tenants", "tenant_provisioning_tasks", "invitations"}) {
      assertThat(fixtures.jdbc().queryForObject("SELECT count(*) FROM " + table, Integer.class))
          .as(table)
          .isOne();
    }
    assertThat(fixtures.mailCount()).isOne();
  }

  @Test
  void switchingAddressesNeverEscapesTheDailyOwnerInvitationCap() throws Exception {
    UUID agency = fixtures.agency("ACTIVE");
    fixtures.agencyAdmin(agency, fixtures.account("admin@example.test", true, true));
    Browser admin = loggedIn("admin@example.test");
    UUID tenant =
        UUID.fromString(
            json(createClinic(
                    admin, agency, UUID.randomUUID().toString(), "Clínica", "a0@example.test"))
                .get("tenantId")
                .asText());
    int accepted = 1;
    HttpResponse<String> last = null;
    for (int i = 1; i <= 6; i++) {
      backdateSends(tenant);
      last = reissue(admin, agency, tenant, "a" + i + "@example.test");
      if (last.statusCode() != 200) break;
      accepted++;
    }
    assertThat(accepted).as("five e-mails per clinic per day").isEqualTo(5);
    assertThat(code(last, 429)).isEqualTo("INVITATION_SEND_LIMIT");
  }

  @Test
  void concurrentCreatesNeverOverrunTheUnfinishedClinicCap() throws Exception {
    UUID agency = fixtures.agency("ACTIVE");
    fixtures.agencyAdmin(agency, fixtures.account("admin@example.test", true, true));
    Browser admin = loggedIn("admin@example.test");
    for (int i = 0; i < 2; i++) {
      createClinic(
          admin, agency, UUID.randomUUID().toString(), "Clínica " + i, "g" + i + "@example.test");
    }
    int racers = 3;
    var barrier = new CyclicBarrier(racers);
    List<Future<Integer>> answers = new ArrayList<>();
    try (var pool = Executors.newFixedThreadPool(racers)) {
      for (int i = 0; i < racers; i++) {
        String name = "Concorrente " + i;
        String email = "c" + i + "@example.test";
        answers.add(
            pool.submit(
                () -> {
                  barrier.await();
                  return createClinic(admin, agency, UUID.randomUUID().toString(), name, email)
                      .statusCode();
                }));
      }
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> answer : answers) statuses.add(answer.get());
      assertThat(statuses).containsExactlyInAnyOrder(201, 409, 409);
    }
    assertThat(
            fixtures
                .jdbc()
                .queryForObject(
                    "SELECT count(*) FROM tenants WHERE agency_id = ?", Integer.class, agency))
        .isEqualTo(3);
  }

  @Test
  void unfinishedClinicsAreCappedAndSuspendedAgenciesStop() throws Exception {
    UUID agency = fixtures.agency("ACTIVE");
    fixtures.agencyAdmin(agency, fixtures.account("admin@example.test", true, true));
    Browser admin = loggedIn("admin@example.test");
    for (int i = 0; i < 3; i++) {
      assertThat(
              createClinic(
                      admin,
                      agency,
                      UUID.randomUUID().toString(),
                      "Clínica " + i,
                      "gestor" + i + "@example.test")
                  .statusCode())
          .isEqualTo(201);
    }
    var capped =
        createClinic(admin, agency, UUID.randomUUID().toString(), "Clínica 3", "g3@example.test");
    assertThat(code(capped, 409)).isEqualTo("AGENCY_PENDING_CLINIC_LIMIT");

    UUID existing =
        fixtures
            .jdbc()
            .queryForObject(
                "SELECT tenant_id FROM tenants WHERE agency_id = ? LIMIT 1", UUID.class, agency);
    var withoutCsrf =
        admin.postJson(
            "/v1/agencies/" + agency + "/clinics",
            "{\"requestId\":\""
                + UUID.randomUUID()
                + "\",\"clinicName\":\"X\",\"ownerEmail\":\"x@example.test\"}",
            false);
    assertThat(code(withoutCsrf, 403)).isEqualTo("CSRF_INVALID");

    fixtures.jdbc().update("UPDATE agencies SET status = 'SUSPENDED' WHERE agency_id = ?", agency);
    assertThat(json(admin.get("/v1/agencies")).size()).isZero();
    assertThat(
            code(
                createClinic(
                    admin, agency, UUID.randomUUID().toString(), "Suspensa", "s@example.test"),
                404))
        .isEqualTo("AGENCY_NOT_FOUND");
    backdateSends(existing);
    assertThat(code(reissue(admin, agency, existing, "s@example.test"), 404))
        .isEqualTo("AGENCY_NOT_FOUND");
    assertThat(admin.get("/v1/agencies/" + agency + "/clinics").statusCode()).isEqualTo(404);
    String adminInvite = platformInvite(agency, "novo-admin@example.test");
    assertThat(adminInvite).as("the platform refuses a suspended agency").isNull();
  }

  @Test
  void platformCommandIsIdempotentAndStaysWithinItsGrants() throws Exception {
    UUID request = UUID.randomUUID();
    String first =
        platform(
            "create-agency",
            "--request-id",
            request.toString(),
            "--name",
            "Agência",
            "--admin-email",
            "admin@example.test");
    String second =
        platform(
            "create-agency",
            "--request-id",
            request.toString(),
            "--name",
            "Agência",
            "--admin-email",
            "admin@example.test");
    assertThat(line(second, "agencyId: ")).isEqualTo(line(first, "agencyId: "));
    assertThat(token(second)).isNotEqualTo(token(first));
    assertThat(
            fixtures
                .jdbc()
                .queryForObject(
                    "SELECT count(*) FROM invitations WHERE kind = 'AGENCY'"
                        + " AND accepted_at IS NULL AND revoked_at IS NULL",
                    Integer.class))
        .isOne();
    assertThat(preview(token(first)).statusCode()).isEqualTo(404);

    assertThatThrownBy(() -> platform("create-agency", "--name"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> platform("drop-everything"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                platform(
                    "create-agency",
                    "--request-id",
                    request.toString(),
                    "--name",
                    "Outro nome",
                    "--admin-email",
                    "admin@example.test"))
        .as("a request id is never reused with another name")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                platform(
                    "invite-agency-admin",
                    "--agency-id",
                    UUID.randomUUID().toString(),
                    "--agency-id",
                    UUID.randomUUID().toString(),
                    "--email",
                    "x@example.test"))
        .as("duplicated flags")
        .isInstanceOf(IllegalArgumentException.class);

    // A pending clinic invitation the operator must neither read nor rotate (V12).
    UUID clinic = fixtures.clinic("Clínica alheia", "AWAITING_OWNER", null);
    UUID owner = fixtures.account("owner@example.test", true, true);
    fixtures.member(clinic, owner, "OWNER", true);
    fixtures
        .jdbc()
        .update(
            "INSERT INTO invitations (token_hash, email, kind, tenant_id, role, invited_by,"
                + " expires_at) VALUES (decode(repeat('11', 32), 'hex'), 'vitima@example.test',"
                + " 'CLINIC', ?, 'VIEWER', ?, now() + interval '1 day')",
            clinic,
            owner);
    JdbcTemplate operator = operator();
    for (String forbidden :
        new String[] {
          "SELECT email FROM invitations",
          "UPDATE invitations SET token_hash = decode(repeat('22', 32), 'hex')"
              + " WHERE kind = 'CLINIC'",
          "INSERT INTO invitations (token_hash, email, kind, agency_id, role, expires_at)"
              + " SELECT decode(repeat('33', 32), 'hex'), 'x@example.test', 'AGENCY', agency_id,"
              + " 'AGENCY_ADMIN', now() + interval '1 day' FROM agencies LIMIT 1"
        }) {
      assertThatThrownBy(() -> operator.execute(forbidden))
          .as(forbidden)
          .hasRootCauseInstanceOf(SQLException.class)
          .rootCause()
          .extracting(cause -> ((SQLException) cause).getSQLState())
          .isEqualTo("42501");
    }
    for (String forbidden :
        new String[] {
          "SELECT count(*) FROM user_accounts",
          "SELECT count(*) FROM tenants",
          "SELECT count(*) FROM tenant_memberships",
          "INSERT INTO agency_memberships (agency_id, subject_id, role)"
              + " VALUES (gen_random_uuid(), gen_random_uuid(), 'AGENCY_ADMIN')"
        }) {
      assertThatThrownBy(() -> operator.execute(forbidden))
          .as(forbidden)
          .hasRootCauseInstanceOf(SQLException.class)
          .rootCause()
          .extracting(cause -> ((SQLException) cause).getSQLState())
          .isEqualTo("42501");
    }
  }

  private void backdateSends(UUID tenant) {
    fixtures
        .jdbc()
        .update(
            "UPDATE invitations SET last_sent_at = last_sent_at - interval '2 minutes'"
                + " WHERE tenant_id = ? AND accepted_at IS NULL AND revoked_at IS NULL",
            tenant);
  }

  private String platform(String... args) {
    var buffer = new ByteArrayOutputStream();
    DriverManagerDataSource source = operatorSource();
    new PlatformCommand(
            new PlatformRepository(new JdbcTemplate(source)),
            new TransactionTemplate(new DataSourceTransactionManager(source)),
            PublicOrigin.of("http://localhost:8088"),
            new PrintStream(buffer, true, StandardCharsets.UTF_8))
        .run(args);
    return buffer.toString(StandardCharsets.UTF_8);
  }

  private String platformInvite(UUID agency, String email) {
    try {
      return platform("invite-agency-admin", "--agency-id", agency.toString(), "--email", email);
    } catch (IllegalArgumentException refused) {
      return null;
    }
  }

  private JdbcTemplate operator() {
    return new JdbcTemplate(operatorSource());
  }

  private static DriverManagerDataSource operatorSource() {
    return new DriverManagerDataSource(
        TestPostgres.url(POSTGRES, POSTGRES.getDatabaseName()),
        TestPostgres.PLATFORM,
        TestPostgres.PASSWORD);
  }

  private static String token(String output) {
    Matcher link = LINK.matcher(output);
    if (!link.find()) throw new AssertionError("No invitation link printed");
    return link.group(1);
  }

  private static String line(String output, String prefix) {
    return output
        .lines()
        .filter(row -> row.startsWith(prefix))
        .findFirst()
        .orElseThrow()
        .substring(prefix.length());
  }

  private HttpResponse<String> createClinic(
      Browser browser, UUID agency, String request, String name, String ownerEmail)
      throws Exception {
    return browser.postJson(
        "/v1/agencies/" + agency + "/clinics",
        "{\"requestId\":\""
            + request
            + "\",\"clinicName\":\""
            + name
            + "\",\"ownerEmail\":\""
            + ownerEmail
            + "\"}",
        true);
  }

  private HttpResponse<String> reissue(Browser browser, UUID agency, UUID tenant, String email)
      throws Exception {
    return browser.postJson(
        "/v1/agencies/" + agency + "/clinics/" + tenant + "/owner-invitation",
        "{\"ownerEmail\":\"" + email + "\"}",
        true);
  }

  private HttpResponse<String> preview(String token) throws Exception {
    return anonymous().postJson("/v1/invitations/preview", "{\"token\":\"" + token + "\"}", true);
  }

  private static HttpResponse<String> accept(Browser browser, String token) throws Exception {
    return browser.postJson(
        "/v1/invitations/accept",
        "{\"token\":\""
            + token
            + "\",\"displayName\":\"Synthetic\",\"password\":\""
            + PASSWORD
            + "\"}",
        true);
  }

  private Browser anonymous() throws Exception {
    Browser browser = new Browser(port);
    browser.csrf();
    return browser;
  }

  private Browser loggedIn(String email) throws Exception {
    Browser browser = anonymous();
    assertThat(browser.login(email, PASSWORD).statusCode()).as("login " + email).isEqualTo(204);
    browser.csrf();
    MfaFixture.complete(browser, fixtures.jdbc());
    return browser;
  }

  private static JsonNode json(HttpResponse<String> response) {
    return Browser.JSON.readTree(response.body());
  }

  /** Asserts the status and returns the error code, or null for a body without one. */
  private static String code(HttpResponse<String> response, int status) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    if (response.body() == null || response.body().isBlank()) return null;
    JsonNode code = Browser.JSON.readTree(response.body()).get("code");
    return code == null ? null : code.asText();
  }

  private static Path temporaryDirectory() {
    try {
      return Files.createTempDirectory("branly-agency-mail");
    } catch (java.io.IOException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
