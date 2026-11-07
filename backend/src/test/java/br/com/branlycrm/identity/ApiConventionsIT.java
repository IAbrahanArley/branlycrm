package br.com.branlycrm.identity;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.branlycrm.support.Browser;
import br.com.branlycrm.support.TestPostgres;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * API conventions of CRM-004 over real HTTP: the {@code {"code","message","fields"}} error body
 * written by Spring Security handlers, filters and controller advices, and the paginated, filtered
 * team list ({@code {"items","page","pageSize","total"}}).
 *
 * <p>The login limiter allows 20 POSTs per minute per address and per application context. This
 * class has its own container, hence its own context and limiter; the regular tests log in only a
 * handful of times and the rate-limit test runs last.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.docker.compose.enabled=false")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiConventionsIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final String PASSWORD = "Synthetic conventions integration secret 2026!";
  // Five slots (ATTENDANT twice) so roles and the i % 4 active flag stay spread out.
  private static final String[] ROLES = {"MANAGER", "ATTENDANT", "VIEWER", "OWNER", "ATTENDANT"};
  private static final int EXTRA_MEMBERS = 29;

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;

  UUID clinicA;
  UUID clinicB;
  UUID ownerA;
  UUID receptionA;
  UUID ownerB;

  /** Members of clinic A as the API must order them: active first, then name, then subject. */
  List<Member> expected;

  record Member(UUID subjectId, String displayName, String role, boolean active) {}

  @BeforeEach
  void resetDisposableFixtures() {
    // Each class owns a disposable container; never clean the developer database.
    // Suspend first: the V6 trigger forbids removing the last owner of an ACTIVE clinic.
    jdbc.update("UPDATE tenants SET status = 'SUSPENDED'");
    jdbc.update("DELETE FROM tenant_provisioning_tasks");
    jdbc.update("DELETE FROM tenant_memberships");
    jdbc.update("DELETE FROM tenants");
    jdbc.update("DELETE FROM user_accounts");

    clinicA = clinic("Clínica A");
    clinicB = clinic("Clínica B");
    expected = new ArrayList<>();

    ownerA = account("owner-a@example.test", "Synthetic member 00", true);
    member(clinicA, ownerA, "OWNER", true);
    receptionA = account("reception-a@example.test", "Synthetic member 01", true);
    member(clinicA, receptionA, "ATTENDANT", true);
    for (int i = 2; i <= EXTRA_MEMBERS; i++) {
      // Some names repeat on purpose so the subject_id tie-break is exercised.
      String name =
          "Synthetic member " + String.format(Locale.ROOT, "%02d", i % 3 == 0 ? i - 1 : i);
      UUID subject = account("member-" + i + "@example.test", name, true);
      member(clinicA, subject, ROLES[i % ROLES.length], i % 4 != 0);
    }
    ownerB = account("owner-b@example.test", "Synthetic member 00", true);
    member(clinicB, ownerB, "OWNER", true);
    member(
        clinicB, account("sales-b@example.test", "Synthetic member 02", true), "ATTENDANT", true);
    // An unverified account answers login exactly like a missing one.
    account("unverified@example.test", "Synthetic member 99", false);

    expected.sort(
        Comparator.comparing((Member m) -> !m.active())
            .thenComparing(Member::displayName)
            .thenComparing(m -> m.subjectId().toString()));
  }

  @Test
  @Order(1)
  void anonymousRequestsGetAuthenticationRequired() throws Exception {
    Browser anonymous = new Browser(port);
    for (String path : List.of("/v1/auth/me", "/v1/clinic/team", "/v1/clinic/team?page=abc")) {
      assertError(anonymous.get(path), 401, "AUTHENTICATION_REQUIRED");
    }
  }

  @Test
  @Order(2)
  void failedLoginsShareOneBodyThatRevealsNothing() throws Exception {
    Browser browser = new Browser(port);
    browser.csrf();
    var wrongPassword = browser.login("owner-a@example.test", "wrong synthetic password");
    var unknown = browser.login("nobody@example.test", PASSWORD);
    var unverified = browser.login("unverified@example.test", PASSWORD);
    assertError(wrongPassword, 401, "INVALID_CREDENTIALS");
    assertError(unknown, 401, "INVALID_CREDENTIALS");
    assertError(unverified, 401, "INVALID_CREDENTIALS");
    assertThat(wrongPassword.body()).isEqualTo(unknown.body()).isEqualTo(unverified.body());
  }

  @Test
  @Order(3)
  void missingOrStaleCsrfTokenGetsCsrfInvalid() throws Exception {
    Browser anonymous = new Browser(port);
    // CSRF is checked before authentication and before the login limiter.
    assertError(anonymous.post("/v1/auth/logout", false), 403, "CSRF_INVALID");
    assertError(anonymous.loginWithoutCsrf("owner-a@example.test", "x"), 403, "CSRF_INVALID");

    Browser owner = loggedIn("owner-a@example.test");
    assertError(owner.post("/v1/auth/logout", false), 403, "CSRF_INVALID");
    assertError(owner.put("/v1/auth/clinic/" + clinicA, false), 403, "CSRF_INVALID");
    assertError(
        owner.putJson("/v1/clinic/team/" + receptionA + "/role", "{\"role\":\"VIEWER\"}", false),
        403,
        "CSRF_INVALID");
    // The session survived the refused logout.
    assertThat(owner.get("/v1/auth/me").statusCode()).isEqualTo(200);
  }

  @Test
  @Order(4)
  void clinicSelectionErrorsUseStableCodes() throws Exception {
    Browser owner = loggedIn("owner-a@example.test");
    assertError(owner.get("/v1/auth/clinic"), 409, "CLINIC_NOT_SELECTED");
    assertError(owner.get("/v1/clinic/team"), 409, "CLINIC_NOT_SELECTED");
    // No clinic selected wins over invalid query parameters.
    assertError(owner.get("/v1/clinic/team?page=0"), 409, "CLINIC_NOT_SELECTED");

    var foreign = owner.put("/v1/auth/clinic/" + clinicB, true);
    var absent = owner.put("/v1/auth/clinic/" + UUID.randomUUID(), true);
    assertError(foreign, 403, "CLINIC_ACCESS_DENIED");
    assertError(absent, 403, "CLINIC_ACCESS_DENIED");
    assertThat(foreign.body()).isEqualTo(absent.body());

    var invalidTenant = owner.put("/v1/auth/clinic/not-a-uuid", true);
    assertInvalid(invalidTenant, "tenantId");
    assertThat(invalidTenant.body()).doesNotContain("not-a-uuid");

    Browser reception = selected("reception-a@example.test");
    assertError(reception.get("/v1/clinic/team"), 403, "CLINIC_ACCESS_DENIED");

    // Revoked membership: the session filter answers before the controller, same code.
    select(owner, clinicA);
    jdbc.update(
        "UPDATE tenant_memberships SET active = false WHERE tenant_id = ? AND subject_id = ?",
        clinicA,
        receptionA);
    assertError(reception.get("/v1/auth/me"), 403, "CLINIC_ACCESS_DENIED");
    assertError(reception.get("/v1/clinic/team"), 409, "CLINIC_NOT_SELECTED");

    // Deactivated account: the session is revoked on the next request.
    jdbc.update("UPDATE user_accounts SET active = false WHERE subject_id = ?", receptionA);
    assertError(reception.get("/v1/auth/me"), 401, "SESSION_REVOKED");
    assertError(reception.get("/v1/auth/me"), 401, "AUTHENTICATION_REQUIRED");
  }

  @Test
  @Order(5)
  void invalidInputNamesOnlyTheOffendingFields() throws Exception {
    Browser owner = selected("owner-a@example.test");
    for (String page :
        List.of("0", "-1", "abc", "1.5", "1e3", "10001", "10000", "99999999999", "1%2C2")) {
      assertInvalid(owner.get("/v1/clinic/team?page=" + page), "page");
    }
    // Repeated parameters are joined by Spring and rejected rather than picking one.
    assertInvalid(owner.get("/v1/clinic/team?page=1&page=2"), "page");
    for (String size : List.of("0", "101", "abc", "-5", "%201")) {
      assertInvalid(owner.get("/v1/clinic/team?pageSize=" + size), "pageSize");
    }
    for (String role : List.of("ADMIN", "owner", "SUPER_ADMIN", "OWNER%27%20OR%201%3D1")) {
      assertInvalid(owner.get("/v1/clinic/team?role=" + role), "role");
    }
    for (String active : List.of("yes", "TRUE", "1", "null")) {
      assertInvalid(owner.get("/v1/clinic/team?active=" + active), "active");
    }

    assertInvalid(
        owner.putJson("/v1/clinic/team/not-a-uuid/role", "{\"role\":\"VIEWER\"}", true),
        "subjectId");
    assertInvalid(owner.delete("/v1/clinic/team/not-a-uuid", true), "subjectId");
    assertInvalid(
        owner.putJson("/v1/clinic/team/" + receptionA + "/role", "{\"role\":\"ADMIN\"}", true),
        "role");
    assertInvalid(owner.putJson("/v1/clinic/team/" + receptionA + "/role", "{}", true), "role");
    assertInvalid(
        owner.putJson("/v1/clinic/team/" + receptionA + "/role", "{\"role\":", true), "body");
    assertThat(role(receptionA)).isEqualTo("ATTENDANT");

    // Empty values fall back to the defaults instead of failing.
    var empty = owner.get("/v1/clinic/team?page=&pageSize=&role=&active=");
    assertThat(empty.statusCode()).isEqualTo(200);
    JsonNode page = Browser.JSON.readTree(empty.body());
    assertThat(page.get("page").asInt()).isOne();
    assertThat(page.get("pageSize").asInt()).isEqualTo(25);
    assertThat(page.get("total").asLong()).isEqualTo(expected.size());
  }

  @Test
  @Order(6)
  void teamListIsPaginatedInAStableOrder() throws Exception {
    Browser owner = selected("owner-a@example.test");
    assertThat(expected).hasSize(EXTRA_MEMBERS + 1);

    JsonNode first = page(owner, "");
    assertThat(fieldNames(first)).containsExactlyInAnyOrder("items", "page", "pageSize", "total");
    assertThat(first.get("page").asInt()).isOne();
    assertThat(first.get("pageSize").asInt()).isEqualTo(25);
    assertThat(first.get("total").asLong()).isEqualTo(30);
    assertThat(first.get("items").size()).isEqualTo(25);

    JsonNode second = page(owner, "?page=2");
    assertThat(second.get("page").asInt()).isEqualTo(2);
    assertThat(second.get("pageSize").asInt()).isEqualTo(25);
    assertThat(second.get("total").asLong()).isEqualTo(30);
    assertThat(second.get("items").size()).isEqualTo(5);

    List<String> seen = new ArrayList<>(subjects(first));
    seen.addAll(subjects(second));
    assertThat(new HashSet<>(seen)).as("no member repeated across pages").hasSize(30);
    assertThat(seen).containsExactlyElementsOf(ids(expected));
    for (JsonNode member : first.get("items")) {
      assertThat(fieldNames(member))
          .containsExactlyInAnyOrder("subjectId", "displayName", "email", "role", "active");
    }
    String bodies = first.toString() + second;
    assertThat(bodies)
        .doesNotContain("owner-b@example.test", "sales-b@example.test", ownerB.toString())
        .doesNotContainIgnoringCase("password")
        .doesNotContainIgnoringCase("hash");

    // Same request, same order: no instability between calls.
    assertThat(subjects(page(owner, ""))).containsExactlyElementsOf(subjects(first));

    // Smaller pages walk the same sequence.
    List<String> walked = new ArrayList<>();
    for (int p = 1; p <= 3; p++) {
      JsonNode slice = page(owner, "?pageSize=10&page=" + p);
      assertThat(slice.get("pageSize").asInt()).isEqualTo(10);
      assertThat(slice.get("total").asLong()).isEqualTo(30);
      walked.addAll(subjects(slice));
    }
    assertThat(walked).containsExactlyElementsOf(ids(expected));
    assertThat(subjects(page(owner, "?pageSize=100"))).containsExactlyElementsOf(ids(expected));
    assertThat(subjects(page(owner, "?pageSize=1&page=30")))
        .containsExactly(expected.get(29).subjectId().toString());

    // Beyond the end: empty items, same total.
    for (String query : List.of("?page=3", "?page=401", "?pageSize=100&page=2")) {
      JsonNode beyond = page(owner, query);
      assertThat(beyond.get("items").size()).as(query).isZero();
      assertThat(beyond.get("total").asLong()).as(query).isEqualTo(30);
    }
  }

  @Test
  @Order(7)
  void unhandledRoutesMethodsAndMediaTypesUseTheStandardBody() throws Exception {
    Browser owner = selected("owner-a@example.test");
    var unknown = owner.get("/v1/does-not-exist");
    assertThat(unknown.statusCode()).isEqualTo(404);
    assertThat(Browser.JSON.readTree(unknown.body()).get("code").asText()).isEqualTo("NOT_FOUND");
    var method = owner.post("/v1/clinic/team", true);
    assertThat(method.statusCode()).isEqualTo(405);
    assertThat(Browser.JSON.readTree(method.body()).get("code").asText())
        .isEqualTo("METHOD_NOT_ALLOWED");
    for (var response : List.of(unknown, method)) {
      assertThat(response.headers().firstValue("Content-Type").orElse(""))
          .startsWith("application/json");
      assertThat(response.body()).doesNotContain("timestamp", "Exception", "trace");
    }
  }

  @Test
  @Order(8)
  void filtersNarrowBothItemsAndTotal() throws Exception {
    Browser owner = selected("owner-a@example.test");
    for (String role : ROLES) {
      assertFiltered(owner, "?role=" + role, m -> m.role().equals(role));
    }
    assertFiltered(owner, "?active=true", Member::active);
    assertFiltered(owner, "?active=false", m -> !m.active());
    assertFiltered(
        owner, "?role=ATTENDANT&active=false", m -> m.role().equals("ATTENDANT") && !m.active());
    assertFiltered(owner, "?role=OWNER&active=true", m -> m.role().equals("OWNER") && m.active());
    assertFiltered(
        owner,
        "?role=MANAGER&active=true&pageSize=2&page=2",
        2,
        2,
        m -> m.role().equals("MANAGER") && m.active());
    assertThat(expected.stream().filter(m -> !m.active()).count()).isPositive();

    // A combination without matches is an empty page, not an error.
    jdbc.update(
        "UPDATE tenant_memberships SET active = true WHERE tenant_id = ? AND role = 'VIEWER'",
        clinicA);
    JsonNode none = page(owner, "?role=VIEWER&active=false");
    assertThat(none.get("items").size()).isZero();
    assertThat(none.get("total").asLong()).isZero();
  }

  /** Must stay last: it exhausts this context's login budget for the current minute. */
  @Test
  @Order(Integer.MAX_VALUE)
  void repeatedLoginsAreRateLimited() throws Exception {
    Browser browser = new Browser(port);
    browser.csrf();
    HttpResponse<String> limited = null;
    int attempts = 0;
    // Earlier tests already spent part of the 20-attempt window; if the window rolls over
    // mid-loop, at most 20 more attempts are needed, so 41 always reaches the limit.
    while (attempts < 41) {
      attempts++;
      var response = browser.login("nobody@example.test", "wrong synthetic password");
      if (response.statusCode() == 429) {
        limited = response;
        break;
      }
      assertError(response, 401, "INVALID_CREDENTIALS");
    }
    assertThat(limited).as("429 after %s attempts", attempts).isNotNull();
    assertError(limited, 429, "RATE_LIMITED");
    assertThat(limited.headers().firstValue("Retry-After")).hasValue("60");
    // Valid credentials are refused too while limited, with the same answer.
    var stillLimited = browser.login("owner-a@example.test", PASSWORD);
    assertError(stillLimited, 429, "RATE_LIMITED");
    assertThat(stillLimited.body()).isEqualTo(limited.body());
  }

  private void assertFiltered(Browser owner, String query, Predicate<Member> filter)
      throws Exception {
    assertFiltered(owner, query, 1, 25, filter);
  }

  private void assertFiltered(
      Browser owner, String query, int page, int pageSize, Predicate<Member> filter)
      throws Exception {
    List<Member> matching = expected.stream().filter(filter).toList();
    assertThat(matching).as(query).isNotEmpty();
    JsonNode body = page(owner, query);
    assertThat(body.get("total").asLong()).as(query).isEqualTo(matching.size());
    int from = Math.min((page - 1) * pageSize, matching.size());
    int to = Math.min(from + pageSize, matching.size());
    assertThat(subjects(body)).as(query).containsExactlyElementsOf(ids(matching.subList(from, to)));
    for (JsonNode member : body.get("items")) {
      Member fixture =
          matching.stream()
              .filter(m -> m.subjectId().toString().equals(member.get("subjectId").asText()))
              .findFirst()
              .orElseThrow();
      assertThat(member.get("role").asText()).isEqualTo(fixture.role());
      assertThat(member.get("active").asBoolean()).isEqualTo(fixture.active());
    }
  }

  private static JsonNode page(Browser browser, String query) throws Exception {
    var response = browser.get("/v1/clinic/team" + query);
    assertThat(response.statusCode()).as(query).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).startsWith("application/json"));
    return Browser.JSON.readTree(response.body());
  }

  private static void assertInvalid(HttpResponse<String> response, String field) {
    JsonNode body = assertError(response, 400, "INVALID_INPUT");
    List<String> fields = new ArrayList<>();
    body.get("fields").forEach(name -> fields.add(name.asText()));
    assertThat(fields).as(response.uri().toString()).containsExactly(field);
  }

  /** Standard error body, JSON, with nothing internal or personal in it. */
  private static JsonNode assertError(HttpResponse<String> response, int status, String code) {
    String where = response.request().method() + " " + response.uri();
    assertThat(response.statusCode()).as(where).isEqualTo(status);
    assertThat(response.headers().firstValue("Content-Type"))
        .as(where)
        .hasValueSatisfying(type -> assertThat(type).startsWith("application/json"));
    JsonNode body = Browser.JSON.readTree(response.body());
    assertThat(fieldNames(body)).as(where).containsExactlyInAnyOrder("code", "message", "fields");
    assertThat(body.get("code").asText()).as(where).isEqualTo(code);
    assertThat(body.get("message").asText()).as(where).isNotBlank();
    assertThat(body.get("fields").isArray()).as(where).isTrue();
    if (!"INVALID_INPUT".equals(code)) assertThat(body.get("fields").size()).as(where).isZero();
    assertThat(response.body())
        .as(where)
        .doesNotContain("Exception", "exception", "\tat ", "br.com.", "org.springframework")
        .doesNotContain("@", "example.test", "tenant_", "user_accounts")
        .doesNotContainIgnoringCase("select ")
        .doesNotContainIgnoringCase("sql")
        .doesNotContainIgnoringCase("password")
        .doesNotContainIgnoringCase("stack");
    return body;
  }

  private static List<String> subjects(JsonNode page) {
    List<String> subjects = new ArrayList<>();
    page.get("items").forEach(member -> subjects.add(member.get("subjectId").asText()));
    return subjects;
  }

  private static List<String> ids(List<Member> members) {
    return members.stream().map(m -> m.subjectId().toString()).toList();
  }

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.propertyNames().forEach(names::add);
    return names;
  }

  private Browser selected(String email) throws Exception {
    Browser browser = loggedIn(email);
    select(browser, clinicA);
    return browser;
  }

  private static void select(Browser browser, UUID clinic) throws Exception {
    assertThat(browser.put("/v1/auth/clinic/" + clinic, true).statusCode()).isEqualTo(200);
  }

  private Browser loggedIn(String email) throws Exception {
    Browser browser = new Browser(port);
    browser.csrf();
    assertThat(browser.login(email, PASSWORD).statusCode()).isEqualTo(204);
    browser.csrf();
    return browser;
  }

  private String role(UUID subject) {
    return jdbc.queryForObject(
        "SELECT role FROM tenant_memberships WHERE tenant_id = ? AND subject_id = ?",
        String.class,
        clinicA,
        subject);
  }

  private UUID account(String email, String displayName, boolean verified) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO user_accounts (subject_id, email, display_name, password_hash, email_verified)"
            + " VALUES (?, ?, ?, ?, ?)",
        id,
        email,
        displayName,
        encoder.encode(PASSWORD),
        verified);
    return id;
  }

  private UUID clinic(String name) {
    UUID id = UUID.randomUUID();
    String suffix = id.toString().replace("-", "");
    // Inserted directly as ACTIVE: the activation trigger fires only on UPDATE of status.
    jdbc.update(
        "INSERT INTO tenants (tenant_id, clinic_name, database_name, database_user,"
            + " credential_reference, status) VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
        id,
        name,
        "clinic_" + suffix,
        "tenant_" + suffix,
        "TENANT_SECRET_" + suffix.toUpperCase(Locale.ROOT));
    return id;
  }

  private void member(UUID tenant, UUID subject, String role, boolean active) {
    jdbc.update(
        "INSERT INTO tenant_memberships (tenant_id, subject_id, role, active) VALUES (?, ?, ?, ?)",
        tenant,
        subject,
        role,
        active);
    if (tenant.equals(clinicA)) {
      String name =
          jdbc.queryForObject(
              "SELECT display_name FROM user_accounts WHERE subject_id = ?", String.class, subject);
      expected.add(new Member(subject, name, role, active));
    }
  }
}
