package br.com.branlycrm.identity;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.branlycrm.support.Browser;
import br.com.branlycrm.support.TestPostgres;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** Real session cookie, CSRF, Spring Security filters and PostgreSQL authorization queries. */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.docker.compose.enabled=false")
class SessionAuthenticationIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final String PASSWORD = "Synthetic session integration secret 2026!";

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;

  UUID ownerA;
  UUID ownerB;
  UUID clinicA;
  UUID clinicB;
  UUID suspendedClinic;
  UUID revokedClinic;

  @BeforeEach
  void resetDisposableFixtures() {
    // Each class owns a disposable container; never clean the developer database.
    // Suspend first: the V6 trigger forbids removing the last owner of an ACTIVE clinic.
    // Only ACTIVE clinics need it; V8 refuses other transitions to SUSPENDED.
    jdbc.update("UPDATE tenants SET status = 'SUSPENDED' WHERE status = 'ACTIVE'");
    jdbc.update("DELETE FROM tenant_provisioning_tasks");
    jdbc.update("DELETE FROM tenant_memberships");
    jdbc.update("DELETE FROM tenants");
    jdbc.update("DELETE FROM user_accounts");
    ownerA = account("owner-a@example.test", true);
    ownerB = account("owner-b@example.test", true);
    // A second owner lets the tests revoke owner A without hitting the last-owner invariant.
    UUID coOwnerA = account("co-owner-a@example.test", true);
    account("unverified@example.test", false);
    // Inserted directly as ACTIVE: the activation trigger fires only on UPDATE of status.
    clinicA = clinic("Clínica A", "ACTIVE");
    clinicB = clinic("Clínica B", "ACTIVE");
    suspendedClinic = clinic("Clínica Suspensa", "SUSPENDED");
    revokedClinic = clinic("Clínica Revogada", "ACTIVE");
    member(clinicA, ownerA, "OWNER", true);
    member(clinicA, coOwnerA, "OWNER", true);
    member(clinicB, ownerB, "OWNER", true);
    member(suspendedClinic, ownerA, "OWNER", true);
    member(revokedClinic, ownerA, "ATTENDANT", false);
  }

  @Test
  void loginSelectionAndLogoutFollowTheSessionProtocol() throws Exception {
    Browser browser = new Browser(port);
    assertThat(browser.get("/v1/auth/me").statusCode()).isEqualTo(401);

    var csrf = browser.csrf();
    assertThat(csrf.headers().allValues("Set-Cookie"))
        .anySatisfy(
            cookie ->
                assertThat(cookie)
                    .startsWith("BRANLY_SESSION=")
                    .contains("HttpOnly", "SameSite=Lax"));
    assertThat(browser.loginWithoutCsrf("owner-a@example.test", PASSWORD).statusCode())
        .isEqualTo(403);
    assertThat(browser.login("owner-a@example.test", "wrong password").statusCode()).isEqualTo(401);
    var login = browser.login(" OWNER-A@example.test ", PASSWORD);
    assertThat(login.statusCode()).isEqualTo(204);
    assertThat(login.headers().firstValue("Location")).isEmpty();

    var me = browser.get("/v1/auth/me");
    assertThat(me.statusCode()).isEqualTo(200);
    JsonNode identity = Browser.JSON.readTree(me.body());
    assertThat(identity.get("subjectId").asText()).isEqualTo(ownerA.toString());
    assertThat(identity.get("email").asText()).isEqualTo("owner-a@example.test");
    assertThat(me.body()).doesNotContain("password", "bcrypt", "authVersion");

    JsonNode clinics = Browser.JSON.readTree(browser.get("/v1/auth/clinics").body());
    assertThat(clinics.size()).isOne();
    assertThat(clinics.get(0).get("tenantId").asText()).isEqualTo(clinicA.toString());
    assertThat(clinics.get(0).get("clinicName").asText()).isEqualTo("Clínica A");
    assertThat(clinics.get(0).get("role").asText()).isEqualTo("OWNER");

    // The token rotates on login: the pre-login token must no longer authorize mutations.
    assertThat(browser.put("/v1/auth/clinic/" + clinicA, true).statusCode()).isEqualTo(403);
    browser.csrf();
    assertThat(browser.put("/v1/auth/clinic/" + clinicA, false).statusCode()).isEqualTo(403);
    var foreign = browser.put("/v1/auth/clinic/" + clinicB, true);
    var absent = browser.put("/v1/auth/clinic/" + UUID.randomUUID(), true);
    assertThat(foreign.statusCode()).isEqualTo(403);
    assertThat(absent.statusCode()).isEqualTo(403);
    assertThat(foreign.body()).isEqualTo(absent.body());
    assertThat(browser.put("/v1/auth/clinic/" + suspendedClinic, true).statusCode()).isEqualTo(403);
    assertThat(browser.put("/v1/auth/clinic/" + revokedClinic, true).statusCode()).isEqualTo(403);
    assertThat(browser.put("/v1/auth/clinic/not-a-uuid", true).statusCode()).isEqualTo(400);
    var selected = browser.put("/v1/auth/clinic/" + clinicA, true);
    assertThat(selected.statusCode()).isEqualTo(200);
    assertThat(Browser.JSON.readTree(selected.body()).get("tenantId").asText())
        .isEqualTo(clinicA.toString());

    assertThat(browser.post("/v1/auth/logout", false).statusCode()).isEqualTo(403);
    assertThat(browser.post("/v1/auth/logout", true).statusCode()).isEqualTo(204);
    assertThat(browser.get("/v1/auth/me").statusCode()).isEqualTo(401);
  }

  @Test
  void revokingMembershipOrAccountTakesEffectOnTheNextRequest() throws Exception {
    Browser browser = loggedIn("owner-a@example.test");
    assertThat(browser.put("/v1/auth/clinic/" + clinicA, true).statusCode()).isEqualTo(200);

    jdbc.update(
        "UPDATE tenant_memberships SET active = false WHERE tenant_id = ? AND subject_id = ?",
        clinicA,
        ownerA);
    assertThat(browser.get("/v1/auth/me").statusCode()).isEqualTo(403);
    // Revocation drops the selection only; the global identity stays valid for other clinics.
    assertThat(browser.get("/v1/auth/me").statusCode()).isEqualTo(200);

    jdbc.update("UPDATE user_accounts SET active = false WHERE subject_id = ?", ownerA);
    assertThat(browser.get("/v1/auth/me").statusCode()).isEqualTo(401);
    assertThat(browser.get("/v1/auth/me").statusCode()).isEqualTo(401);
    browser.csrf();
    assertThat(browser.login("owner-a@example.test", PASSWORD).statusCode()).isEqualTo(401);
  }

  @Test
  void suspendingTheSelectedClinicBlocksTheNextRequest() throws Exception {
    Browser browser = loggedIn("owner-a@example.test");
    assertThat(browser.put("/v1/auth/clinic/" + clinicA, true).statusCode()).isEqualTo(200);
    jdbc.update("UPDATE tenants SET status = 'SUSPENDED' WHERE tenant_id = ?", clinicA);
    assertThat(browser.get("/v1/auth/clinics").statusCode()).isEqualTo(403);
    JsonNode clinics = Browser.JSON.readTree(browser.get("/v1/auth/clinics").body());
    assertThat(clinics.size()).isZero();
  }

  @Test
  void unverifiedAndUnknownAccountsFailWithTheSameResponse() throws Exception {
    Browser browser = new Browser(port);
    browser.csrf();
    var unverified = browser.login("unverified@example.test", PASSWORD);
    var unknown = browser.login("nobody@example.test", PASSWORD);
    assertThat(unverified.statusCode()).isEqualTo(401);
    assertThat(unknown.statusCode()).isEqualTo(401);
    assertThat(unverified.body()).isEqualTo(unknown.body());
  }

  @Test
  void apiDocumentationDescribesFilterRoutesAndSessionSecurity() throws Exception {
    var docs = loggedIn("owner-a@example.test").get("/v3/api-docs");
    assertThat(docs.statusCode()).isEqualTo(200);
    JsonNode api = Browser.JSON.readTree(docs.body());
    assertThat(api.get("paths").has("/v1/auth/login")).isTrue();
    assertThat(api.get("paths").has("/v1/auth/logout")).isTrue();
    assertThat(api.get("paths").has("/v1/auth/clinic/{tenantId}")).isTrue();
    assertThat(api.at("/components/securitySchemes/session/name").asText())
        .isEqualTo("BRANLY_SESSION");
  }

  private Browser loggedIn(String email) throws Exception {
    Browser browser = new Browser(port);
    browser.csrf();
    assertThat(browser.login(email, PASSWORD).statusCode()).isEqualTo(204);
    browser.csrf();
    return browser;
  }

  private UUID account(String email, boolean verified) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO user_accounts (subject_id, email, display_name, password_hash, email_verified)"
            + " VALUES (?, ?, ?, ?, ?)",
        id,
        email,
        "Synthetic user",
        encoder.encode(PASSWORD),
        verified);
    return id;
  }

  private UUID clinic(String name, String status) {
    UUID id = UUID.randomUUID();
    String suffix = id.toString().replace("-", "");
    jdbc.update(
        "INSERT INTO tenants (tenant_id, clinic_name, database_name, database_user,"
            + " credential_reference, status) VALUES (?, ?, ?, ?, ?, ?)",
        id,
        name,
        "clinic_" + suffix,
        "tenant_" + suffix,
        "TENANT_SECRET_" + suffix.toUpperCase(Locale.ROOT),
        status);
    return id;
  }

  private void member(UUID tenant, UUID subject, String role, boolean active) {
    jdbc.update(
        "INSERT INTO tenant_memberships (tenant_id, subject_id, role, active) VALUES (?, ?, ?, ?)",
        tenant,
        subject,
        role,
        active);
  }
}
