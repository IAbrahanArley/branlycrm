package br.com.branlycrm.onboarding;

import static br.com.branlycrm.onboarding.InvitationFixtures.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.branlycrm.support.Browser;
import br.com.branlycrm.support.MfaFixture;
import br.com.branlycrm.support.TestPostgres;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Team invitations and their acceptance over real HTTP (cookies, CSRF, Spring Security), with the
 * application connected as crm_control_runtime so the V8 and V9 grants are exercised for real
 * (ADR-028, CRM-013). E-mails are files written by the local adapter.
 *
 * <p>The limiter allows 20 logins and 20 acceptances per minute per address and context: this class
 * stays below both.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.docker.compose.enabled=false",
      "app.mail.provider=local",
      MfaFixture.KEY_PROPERTY
    })
class TeamInvitationIT {
  @Container static final PostgreSQLContainer POSTGRES = TestPostgres.container();
  static final Path MAIL = temporaryDirectory();

  @LocalServerPort int port;
  @Autowired PasswordEncoder encoder;

  InvitationFixtures fixtures;
  UUID clinicA;
  UUID clinicB;
  UUID ownerA;
  UUID managerA;
  UUID attendantA;
  UUID formerA;
  UUID ownerB;

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
    clinicA = fixtures.clinic("Clínica A", "ACTIVE", null);
    clinicB = fixtures.clinic("Clínica B", "ACTIVE", null);
    ownerA = fixtures.account("owner-a@example.test", true, true);
    managerA = fixtures.account("manager-a@example.test", true, true);
    attendantA = fixtures.account("attendant-a@example.test", true, true);
    formerA = fixtures.account("former-a@example.test", true, true);
    ownerB = fixtures.account("owner-b@example.test", true, true);
    fixtures.member(clinicA, ownerA, "OWNER", true);
    fixtures.member(clinicA, managerA, "MANAGER", true);
    fixtures.member(clinicA, attendantA, "ATTENDANT", true);
    fixtures.member(clinicA, formerA, "ATTENDANT", false);
    fixtures.member(clinicB, ownerB, "OWNER", true);
  }

  @Test
  void ownerInvitesSomeoneNewWhoAcceptsWithoutAnAccountAndThenLogsIn() throws Exception {
    Browser owner = selected("owner-a@example.test", clinicA);
    var issued = invite(owner, "new-member@example.test", "ATTENDANT");
    assertThat(issued.statusCode()).isEqualTo(201);
    JsonNode body = Browser.JSON.readTree(issued.body());
    assertThat(body.get("delivery").asText()).isEqualTo("SENT");
    assertThat(body.get("role").asText()).isEqualTo("ATTENDANT");
    String token = fixtures.lastMailedToken();
    assertThat(issued.body()).doesNotContain(token);
    assertThat(
            fixtures
                .jdbc()
                .queryForObject(
                    "SELECT count(*) FROM invitations WHERE token_hash = convert_to(?, 'UTF8')",
                    Integer.class,
                    token))
        .as("only the hash is stored")
        .isZero();

    Browser anonymous = anonymous();
    var preview = anonymous.postJson("/v1/invitations/preview", tokenBody(token), true);
    assertThat(preview.statusCode()).isEqualTo(200);
    JsonNode view = Browser.JSON.readTree(preview.body());
    assertThat(view.get("targetName").asText()).isEqualTo("Clínica A");
    assertThat(view.get("requiresLogin").asBoolean()).isFalse();

    assertThat(code(accept(anonymous, token, null, null))).isEqualTo("INVALID_INPUT");
    assertThat(accept(anonymous, token, "Synthetic New", "short").statusCode()).isEqualTo(400);
    assertThat(accept(anonymous, token, "Synthetic New", PASSWORD).statusCode()).isEqualTo(204);
    UUID member = fixtures.subject("new-member@example.test");
    assertThat(fixtures.role(clinicA, member)).isEqualTo("ATTENDANT");
    var reused = accept(anonymous(), token, "Synthetic New", PASSWORD);
    assertThat(reused.statusCode()).isEqualTo(404);
    assertThat(code(reused)).isEqualTo("INVITATION_INVALID");

    Browser joined = selected("new-member@example.test", clinicA);
    assertThat(joined.get("/v1/auth/clinic").statusCode()).isEqualTo(200);
  }

  @Test
  void unknownExpiredRevokedAndRotatedTokensAreInvalidAlike() throws Exception {
    Browser owner = selected("owner-a@example.test", clinicA);
    invite(owner, "rotated@example.test", "VIEWER");
    String first = fixtures.lastMailedToken();
    UUID invitation = invitationId("rotated@example.test");
    var tooSoon = owner.post("/v1/clinic/team/invitations/" + invitation + "/resend", true);
    assertThat(tooSoon.statusCode()).as("one e-mail per minute per invitation").isEqualTo(429);
    assertThat(code(tooSoon)).isEqualTo("INVITATION_SEND_LIMIT");
    fixtures
        .jdbc()
        .update(
            "UPDATE invitations SET last_sent_at = now() - interval '2 minutes'"
                + " WHERE invitation_id = ?",
            invitation);
    var resent = owner.post("/v1/clinic/team/invitations/" + invitation + "/resend", true);
    assertThat(resent.statusCode()).isEqualTo(200);
    String second = fixtures.lastMailedToken();
    assertThat(second).isNotEqualTo(first);

    invite(owner, "revoked@example.test", "VIEWER");
    String revoked = fixtures.lastMailedToken();
    assertThat(
            owner
                .delete("/v1/clinic/team/invitations/" + invitationId("revoked@example.test"), true)
                .statusCode())
        .isEqualTo(204);

    // Expiry is a database time: push it into the past.
    fixtures
        .jdbc()
        .update(
            "UPDATE invitations SET expires_at = created_at + interval '1 millisecond'"
                + " WHERE invitation_id = ?",
            invitation);

    Browser anonymous = anonymous();
    String unknown = "A".repeat(43);
    var expected = anonymous.postJson("/v1/invitations/preview", tokenBody(unknown), true);
    assertThat(expected.statusCode()).isEqualTo(404);
    for (String token : new String[] {first, second, revoked, "not-a-token"}) {
      var answer = anonymous.postJson("/v1/invitations/preview", tokenBody(token), true);
      assertThat(answer.statusCode()).as(token).isEqualTo(404);
      assertThat(answer.body()).as(token).isEqualTo(expected.body());
    }
    // Expired invitations stay listed so they can be resent.
    JsonNode pending =
        Browser.JSON.readTree(owner.get("/v1/clinic/team/invitations").body()).get("items");
    assertThat(pending.size()).isOne();
    assertThat(pending.get(0).get("expired").asBoolean()).isTrue();
  }

  @Test
  void existingAccountsAreNeverTakenOverNorAbsorbedByAnotherSession() throws Exception {
    UUID existing = fixtures.account("existing@example.test", true, true);
    String hash = hash(existing);
    Browser owner = selected("owner-a@example.test", clinicA);
    invite(owner, "existing@example.test", "VIEWER");
    String token = fixtures.lastMailedToken();

    var anonymous = accept(anonymous(), token, "Attacker", "attacker-password-2026");
    assertThat(anonymous.statusCode()).isEqualTo(409);
    assertThat(code(anonymous)).isEqualTo("INVITATION_LOGIN_REQUIRED");
    Browser other = loggedIn("attendant-a@example.test");
    var mismatch = accept(other, token, null, null);
    assertThat(code(mismatch)).isEqualTo("INVITATION_ACCOUNT_MISMATCH");
    assertThat(fixtures.role(clinicA, attendantA)).isEqualTo("ATTENDANT");

    Browser invited = loggedIn("existing@example.test");
    assertThat(accept(invited, token, "Ignored", "ignored-password-2026").statusCode())
        .isEqualTo(204);
    assertThat(fixtures.role(clinicA, existing)).isEqualTo("VIEWER");
    assertThat(hash(existing))
        .as("the password of a verified account never changes")
        .isEqualTo(hash);
  }

  @Test
  void inactiveAccountsAreRefusedAndUnverifiedOnesAreClaimedByTheToken() throws Exception {
    UUID inactive = fixtures.account("inactive@example.test", false, true);
    UUID unverified = fixtures.account("unverified@example.test", true, false);
    long versionBefore = authVersion(unverified);
    String hashBefore = hash(unverified);
    Browser owner = selected("owner-a@example.test", clinicA);
    invite(owner, "inactive@example.test", "VIEWER");
    String inactiveToken = fixtures.lastMailedToken();
    invite(owner, "unverified@example.test", "VIEWER");
    String unverifiedToken = fixtures.lastMailedToken();

    Browser anonymous = anonymous();
    assertThat(code(accept(anonymous, inactiveToken, "Synthetic", PASSWORD)))
        .isEqualTo("INVITATION_INVALID");
    assertThat(fixtures.role(clinicA, inactive)).isNull();
    assertThat(
            accept(anonymous, unverifiedToken, "Synthetic Claimed", "claimed-password-2026")
                .statusCode())
        .isEqualTo(204);
    assertThat(hash(unverified)).isNotEqualTo(hashBefore);
    assertThat(authVersion(unverified)).as("old sessions are revoked").isGreaterThan(versionBefore);
    assertThat(fixtures.role(clinicA, unverified)).isEqualTo("VIEWER");
    Browser claimed = new Browser(port);
    claimed.csrf();
    assertThat(claimed.login("unverified@example.test", "claimed-password-2026").statusCode())
        .isEqualTo(204);
  }

  @Test
  void invitationReachFollowsTheActorRoleAndFormerMembersComeBack() throws Exception {
    Browser manager = selected("manager-a@example.test", clinicA);
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      var refused = invite(manager, "promoted@example.test", role);
      assertThat(refused.statusCode()).as(role).isEqualTo(403);
      assertThat(code(refused)).isEqualTo("INVITATION_FORBIDDEN");
    }
    assertThat(code(invite(manager, "promoted@example.test", "SALES"))).isEqualTo("INVALID_INPUT");
    assertThat(code(invite(manager, "bad-address", "VIEWER"))).isEqualTo("INVALID_INPUT");
    assertThat(invite(manager, "viewer@example.test", "VIEWER").statusCode()).isEqualTo(201);
    assertThat(code(invite(manager, "attendant-a@example.test", "VIEWER")))
        .isEqualTo("MEMBER_ALREADY_ACTIVE");

    Browser attendant = selected("attendant-a@example.test", clinicA);
    var denied = invite(attendant, "someone@example.test", "VIEWER");
    assertThat(denied.statusCode()).isEqualTo(403);
    assertThat(code(denied)).isEqualTo("CLINIC_ACCESS_DENIED");
    assertThat(attendant.get("/v1/clinic/team/invitations").statusCode()).isEqualTo(403);

    // The owner's pending OWNER invitation is beyond the manager's reach.
    Browser owner = selected("owner-a@example.test", clinicA);
    invite(owner, "future-owner@example.test", "OWNER");
    UUID ownerInvitation = invitationId("future-owner@example.test");
    assertThat(
            manager
                .post("/v1/clinic/team/invitations/" + ownerInvitation + "/resend", true)
                .statusCode())
        .isEqualTo(403);
    assertThat(manager.delete("/v1/clinic/team/invitations/" + ownerInvitation, true).statusCode())
        .isEqualTo(403);
    assertThat(code(invite(manager, "future-owner@example.test", "VIEWER")))
        .as("nor can it rewrite that invitation")
        .isEqualTo("INVITATION_FORBIDDEN");

    // An inactive member returns with the invited role.
    invite(owner, "former-a@example.test", "VIEWER");
    String token = fixtures.lastMailedToken();
    Browser former = loggedIn("former-a@example.test");
    assertThat(accept(former, token, null, null).statusCode()).isEqualTo(204);
    assertThat(fixtures.role(clinicA, formerA)).isEqualTo("VIEWER");
  }

  @Test
  void demotedIssuersLoseTheirInvitationsAndOtherClinicsNeverSeeThem() throws Exception {
    Browser manager = selected("manager-a@example.test", clinicA);
    invite(manager, "orphan@example.test", "VIEWER");
    String token = fixtures.lastMailedToken();
    UUID invitation = invitationId("orphan@example.test");
    fixtures
        .jdbc()
        .update("UPDATE tenant_memberships SET role = 'ATTENDANT' WHERE subject_id = ?", managerA);
    assertThat(code(accept(anonymous(), token, "Synthetic Orphan", PASSWORD)))
        .isEqualTo("INVITATION_INVALID");
    assertThat(fixtures.subject("orphan@example.test")).as("no account left behind").isNull();

    Browser foreign = selected("owner-b@example.test", clinicB);
    var resend = foreign.post("/v1/clinic/team/invitations/" + invitation + "/resend", true);
    var revoke = foreign.delete("/v1/clinic/team/invitations/" + invitation, true);
    var absent = foreign.delete("/v1/clinic/team/invitations/" + UUID.randomUUID(), true);
    assertThat(resend.statusCode()).isEqualTo(404);
    assertThat(revoke.statusCode()).isEqualTo(404);
    assertThat(revoke.body()).isEqualTo(absent.body());
    assertThat(foreign.get("/v1/clinic/team/invitations").body())
        .doesNotContain("orphan@example.test");
    assertThat(
            fixtures
                .jdbc()
                .queryForObject(
                    "SELECT revoked_at IS NULL FROM invitations WHERE invitation_id = ?",
                    Boolean.class,
                    invitation))
        .isTrue();
  }

  private HttpResponse<String> invite(Browser browser, String email, String role) throws Exception {
    return browser.postJson(
        "/v1/clinic/team/invitations",
        "{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}",
        true);
  }

  private static HttpResponse<String> accept(
      Browser browser, String token, String displayName, String password) throws Exception {
    StringBuilder json = new StringBuilder("{\"token\":\"" + token + "\"");
    if (displayName != null) json.append(",\"displayName\":\"").append(displayName).append('"');
    if (password != null) json.append(",\"password\":\"").append(password).append('"');
    return browser.postJson("/v1/invitations/accept", json.append('}').toString(), true);
  }

  private static String tokenBody(String token) {
    return "{\"token\":\"" + token + "\"}";
  }

  private UUID invitationId(String email) {
    return fixtures
        .jdbc()
        .queryForObject(
            "SELECT invitation_id FROM invitations WHERE email = ? AND accepted_at IS NULL"
                + " AND revoked_at IS NULL",
            UUID.class,
            email);
  }

  private String hash(UUID subject) {
    return fixtures
        .jdbc()
        .queryForObject(
            "SELECT password_hash FROM user_accounts WHERE subject_id = ?", String.class, subject);
  }

  private long authVersion(UUID subject) {
    return fixtures
        .jdbc()
        .queryForObject(
            "SELECT auth_version FROM user_accounts WHERE subject_id = ?", Long.class, subject);
  }

  private Browser anonymous() throws Exception {
    Browser browser = new Browser(port);
    browser.csrf();
    return browser;
  }

  private Browser loggedIn(String email) throws Exception {
    Browser browser = new Browser(port);
    browser.csrf();
    assertThat(browser.login(email, PASSWORD).statusCode()).as("login " + email).isEqualTo(204);
    browser.csrf();
    MfaFixture.complete(browser, fixtures.jdbc());
    return browser;
  }

  private Browser selected(String email, UUID clinic) throws Exception {
    Browser browser = loggedIn(email);
    assertThat(browser.put("/v1/auth/clinic/" + clinic, true).statusCode()).isEqualTo(200);
    return browser;
  }

  private static String code(HttpResponse<String> response) {
    return Browser.JSON.readTree(response.body()).get("code").asText();
  }

  private static Path temporaryDirectory() {
    try {
      return Files.createTempDirectory("branly-invitation-mail");
    } catch (java.io.IOException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
