package br.com.branlycrm.identity.mfa;

import static br.com.branlycrm.identity.mfa.MfaSupport.assertError;
import static br.com.branlycrm.identity.mfa.MfaSupport.codeBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.identity.mfa.crypto.Base32;
import br.com.branlycrm.identity.mfa.domain.SealedSecret;
import br.com.branlycrm.support.Browser;
import br.com.branlycrm.support.MfaFixture;
import br.com.branlycrm.support.MutableClock;
import br.com.branlycrm.support.MutableClockConfiguration;
import br.com.branlycrm.support.TestPostgres;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Enrollment, obligation, replacement and removal of the TOTP factor (CRM-016) over real HTTP, with
 * the application as crm_control_runtime and a clock the test moves. Stays below 20 logins.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.docker.compose.enabled=false",
      "app.mail.provider=local",
      MfaFixture.KEY_PROPERTY
    })
@Import(MutableClockConfiguration.class)
class MfaEnrollmentIT {
  @Container static final PostgreSQLContainer POSTGRES = TestPostgres.container();
  static final Path MAIL = MfaSupport.temporaryDirectory();

  @LocalServerPort int port;
  @Autowired PasswordEncoder encoder;
  @Autowired MutableClock clock;

  MfaSupport mfa;

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
    mfa = new MfaSupport(POSTGRES, encoder, MAIL, port, clock);
    mfa.reset();
  }

  @Test
  void ownerWithoutFactorEnrollsBeforeReachingAnythingElse() throws Exception {
    UUID clinic = mfa.clinic("Clínica A");
    UUID owner = mfa.account("owner@example.test");
    mfa.member(clinic, owner, "OWNER");
    Browser browser = mfa.login("owner@example.test");

    JsonNode status = mfa.status(browser);
    assertThat(status.get("state").asText()).isEqualTo("ENROLLMENT_REQUIRED");
    assertThat(status.get("enrolled").asBoolean()).isFalse();
    assertThat(status.get("required").asBoolean()).isTrue();
    assertThat(status.get("recoveryCodesRemaining").asLong()).isZero();
    for (String path : List.of("/v1/auth/clinics", "/v1/clinic/team", "/v1/agencies")) {
      assertError(browser.get(path), 403, "MFA_ENROLLMENT_REQUIRED");
    }
    String token = "{\"token\":\"" + "A".repeat(43) + "\"}";
    assertError(
        browser.postJson("/v1/invitations/accept", token, true), 403, "MFA_ENROLLMENT_REQUIRED");
    assertError(
        browser.postJson("/v1/invitations/preview", token, true), 403, "MFA_ENROLLMENT_REQUIRED");
    assertError(browser.put("/v1/auth/clinic/" + clinic, true), 403, "MFA_ENROLLMENT_REQUIRED");
    assertError(mfa.verify(browser, "123456"), 403, "MFA_ENROLLMENT_REQUIRED");
    assertError(
        browser.postJson("/v1/auth/mfa/recovery-codes", codeBody("123456"), true),
        403,
        "MFA_ENROLLMENT_REQUIRED");
    assertError(
        browser.deleteJson("/v1/auth/mfa/totp", codeBody("123456"), true),
        403,
        "MFA_ENROLLMENT_REQUIRED");
    assertThat(browser.get("/v1/auth/me").statusCode()).isEqualTo(200);
    assertThat(browser.get("/v1/auth/csrf").statusCode()).isEqualTo(200);
    browser.csrf();

    var setup = browser.post("/v1/auth/mfa/totp/setup", true);
    assertThat(setup.statusCode()).isEqualTo(200);
    assertNoStore(setup);
    JsonNode staged = Browser.JSON.readTree(setup.body());
    String secret = staged.get("secret").asText();
    assertThat(secret).matches("[A-Z2-7]{32}");
    assertThat(staged.get("otpauthUri").asText())
        .startsWith("otpauth://totp/Branly%20CRM:owner%40example.test?secret=" + secret)
        .contains("issuer=Branly%20CRM", "digits=6", "period=30");

    // Stored encrypted, bound to the subject: another subject (AAD) cannot open it.
    Map<String, Object> row = mfa.factorRow(owner);
    assertThat(row.get("secret_ciphertext")).as("not confirmed yet").isNull();
    SealedSecret pending = MfaSupport.pending(row);
    byte[] raw = Base32.decode(secret);
    assertThat(pending.ciphertext()).isNotEqualTo(raw);
    assertThat(new String(pending.ciphertext(), StandardCharsets.ISO_8859_1))
        .doesNotContain(new String(raw, StandardCharsets.ISO_8859_1));
    assertThat(MfaFixture.CRYPTO.open(owner, pending)).isEqualTo(raw);
    assertThatThrownBy(() -> MfaFixture.CRYPTO.open(UUID.randomUUID(), pending))
        .isInstanceOf(IllegalStateException.class);

    String wrong = MfaSupport.wrongCode(raw, clock);
    assertError(mfa.confirm(browser, wrong), 400, "MFA_CODE_INVALID");
    String confirmCode = mfa.code(secret);
    var confirmed = mfa.confirm(browser, confirmCode);
    assertThat(confirmed.statusCode()).isEqualTo(200);
    assertNoStore(confirmed);
    List<String> codes = MfaSupport.recoveryCodes(confirmed);
    assertThat(codes).hasSize(10);
    assertThat(new HashSet<>(codes)).hasSize(10);
    assertThat(codes).allMatch(code -> code.matches("[A-Z2-7]{4}(-[A-Z2-7]{4}){3}"));

    status = mfa.status(browser);
    assertThat(status.get("state").asText()).isEqualTo("COMPLETE");
    assertThat(status.get("enrolled").asBoolean()).isTrue();
    assertThat(status.get("recoveryCodesRemaining").asLong()).isEqualTo(10);
    List<byte[]> hashes = mfa.recoveryHashes(owner);
    assertThat(hashes).hasSize(10);
    for (String code : codes) {
      byte[] plain = code.getBytes(StandardCharsets.US_ASCII);
      assertThat(hashes).noneMatch(hash -> Arrays.equals(hash, plain));
      assertThat(hashes)
          .as("only the HMAC is stored")
          .anyMatch(hash -> Arrays.equals(hash, MfaFixture.CRYPTO.hashRecoveryCode(owner, code)));
    }
    assertThat(browser.get("/v1/auth/clinics").statusCode()).isEqualTo(200);
    assertThat(browser.put("/v1/auth/clinic/" + clinic, true).statusCode()).isEqualTo(200);
    assertThat(browser.get("/v1/clinic/team").statusCode()).isEqualTo(200);
    assertError(mfa.verify(browser, mfa.code(secret)), 409, "MFA_ALREADY_COMPLETE");
    assertThat(mfa.mailSubjects()).containsExactly("Segundo fator cadastrado");
    assertThat(mfa.allMail()).doesNotContain(secret).doesNotContain(codes.get(0));

    // The confirming code never serves for a login; the next step does.
    Browser again = mfa.login("owner@example.test");
    JsonNode pendingStatus = mfa.status(again);
    assertThat(pendingStatus.get("state").asText()).isEqualTo("MFA_PENDING");
    assertThat(pendingStatus.get("recoveryCodesRemaining").asLong())
        .as("nothing about the codes before the factor is proved")
        .isZero();
    assertError(again.get("/v1/auth/clinics"), 401, "MFA_REQUIRED");
    assertError(mfa.verify(again, confirmCode), 400, "MFA_CODE_INVALID");
    clock.nextStep();
    assertThat(mfa.verify(again, mfa.code(secret)).statusCode()).isEqualTo(204);
    assertThat(again.get("/v1/auth/clinics").statusCode()).isEqualTo(200);
  }

  @Test
  void agencyAdminWithoutFactorEnrollsBeforeReachingTheAgency() throws Exception {
    UUID agency = mfa.agency();
    mfa.agencyAdmin(agency, mfa.account("admin@example.test"));
    Browser admin = mfa.login("admin@example.test");
    assertThat(mfa.state(admin)).isEqualTo("ENROLLMENT_REQUIRED");
    assertError(admin.get("/v1/agencies"), 403, "MFA_ENROLLMENT_REQUIRED");
    assertError(admin.get("/v1/agencies/" + agency + "/clinics"), 403, "MFA_ENROLLMENT_REQUIRED");
    mfa.enroll(admin, new String[1]);
    var agencies = admin.get("/v1/agencies");
    assertThat(agencies.statusCode()).isEqualTo(200);
    assertThat(agencies.body()).contains(agency.toString());
  }

  @Test
  void promotionToOwnerMidSessionRequiresEnrollmentOnTheNextRequest() throws Exception {
    UUID clinic = mfa.clinic("Clínica A");
    UUID attendant = mfa.account("attendant@example.test");
    mfa.member(clinic, attendant, "ATTENDANT");
    Browser browser = mfa.login("attendant@example.test");
    JsonNode status = mfa.status(browser);
    assertThat(status.get("state").asText()).isEqualTo("COMPLETE");
    assertThat(status.get("required").asBoolean()).isFalse();
    assertThat(browser.get("/v1/auth/clinics").statusCode()).isEqualTo(200);

    mfa.superuser.update(
        "UPDATE tenant_memberships SET role = 'OWNER' WHERE subject_id = ?", attendant);
    assertError(browser.get("/v1/auth/clinics"), 403, "MFA_ENROLLMENT_REQUIRED");
    status = mfa.status(browser);
    assertThat(status.get("state").asText()).isEqualTo("ENROLLMENT_REQUIRED");
    assertThat(status.get("required").asBoolean()).isTrue();
  }

  @Test
  void optionalFactorIsAskedForOnceSetAndRemovalEndsEverySession() throws Exception {
    UUID clinic = mfa.clinic("Clínica A");
    mfa.member(clinic, mfa.account("attendant@example.test"), "ATTENDANT");
    Browser browser = mfa.login("attendant@example.test");
    assertThat(browser.get("/v1/auth/clinics").statusCode()).isEqualTo(200);
    String[] secret = new String[1];
    mfa.enroll(browser, secret);
    assertThat(mfa.state(browser)).isEqualTo("COMPLETE");
    assertThat(browser.post("/v1/auth/logout", true).statusCode()).isEqualTo(204);

    browser = mfa.login("attendant@example.test");
    assertThat(mfa.state(browser)).isEqualTo("MFA_PENDING");
    assertError(browser.get("/v1/auth/clinics"), 401, "MFA_REQUIRED");
    clock.nextStep();
    assertThat(mfa.verify(browser, mfa.code(secret[0])).statusCode()).isEqualTo(204);
    assertThat(browser.get("/v1/auth/clinics").statusCode()).isEqualTo(200);

    assertError(browser.delete("/v1/auth/mfa/totp", true), 400, "INVALID_INPUT");
    assertError(
        browser.deleteJson("/v1/auth/mfa/totp", codeBody("12"), true), 400, "INVALID_INPUT");
    String wrong = MfaSupport.wrongCode(Base32.decode(secret[0]), clock);
    assertError(
        browser.deleteJson("/v1/auth/mfa/totp", codeBody(wrong), true), 400, "MFA_CODE_INVALID");
    clock.nextStep();
    var removed = browser.deleteJson("/v1/auth/mfa/totp", codeBody(mfa.code(secret[0])), true);
    assertThat(removed.statusCode()).isEqualTo(204);
    assertError(browser.get("/v1/auth/me"), 401, "SESSION_REVOKED");
    assertThat(mfa.mailSubjects())
        .containsExactly("Segundo fator cadastrado", "Segundo fator desativado");

    browser = mfa.login("attendant@example.test");
    JsonNode status = mfa.status(browser);
    assertThat(status.get("state").asText()).isEqualTo("COMPLETE");
    assertThat(status.get("enrolled").asBoolean()).isFalse();
  }

  @Test
  void ownersKeepTheFactorAndReplacingItEndsEverySession() throws Exception {
    UUID owner = mfa.owner("owner@example.test");
    Browser first = mfa.login("owner@example.test");
    String[] secret = new String[1];
    mfa.enroll(first, secret);
    String oldSecret = secret[0];
    clock.nextStep();
    var mandatory = first.deleteJson("/v1/auth/mfa/totp", codeBody(mfa.code(oldSecret)), true);
    assertError(mandatory, 403, "MFA_MANDATORY");
    assertThat(mfa.status(first).get("enrolled").asBoolean()).isTrue();

    Browser second = mfa.login("owner@example.test");
    clock.nextStep();
    assertThat(mfa.verify(second, mfa.code(oldSecret)).statusCode()).isEqualTo(204);

    assertError(first.post("/v1/auth/mfa/totp/setup", true), 400, "INVALID_INPUT");
    String wrong = MfaSupport.wrongCode(Base32.decode(oldSecret), clock);
    assertError(
        first.postJson("/v1/auth/mfa/totp/setup", codeBody(wrong), true), 400, "MFA_CODE_INVALID");
    clock.nextStep();
    var restaged = first.postJson("/v1/auth/mfa/totp/setup", codeBody(mfa.code(oldSecret)), true);
    assertThat(restaged.statusCode()).isEqualTo(200);
    String newSecret = Browser.JSON.readTree(restaged.body()).get("secret").asText();
    // The confirmed factor keeps working until the new one is confirmed.
    Map<String, Object> row = mfa.factorRow(owner);
    assertThat(MfaFixture.CRYPTO.open(owner, MfaSupport.confirmed(row)))
        .isEqualTo(Base32.decode(oldSecret));
    assertThat(second.get("/v1/auth/clinics").statusCode()).isEqualTo(200);

    String replacementCode = mfa.code(newSecret);
    var replaced = mfa.confirm(first, replacementCode);
    assertThat(replaced.statusCode()).isEqualTo(200);
    assertThat(MfaSupport.recoveryCodes(replaced)).hasSize(10);
    assertError(first.get("/v1/auth/me"), 401, "SESSION_REVOKED");
    assertError(second.get("/v1/auth/me"), 401, "SESSION_REVOKED");
    assertThat(mfa.mailSubjects())
        .containsExactly("Segundo fator cadastrado", "Segundo fator trocado");

    Browser third = mfa.login("owner@example.test");
    // The code that confirmed the replacement, in the same step, never serves for a login.
    assertError(mfa.verify(third, replacementCode), 400, "MFA_CODE_INVALID");
    clock.nextStep();
    assertError(mfa.verify(third, mfa.code(oldSecret)), 400, "MFA_CODE_INVALID");
    assertThat(mfa.verify(third, mfa.code(newSecret)).statusCode()).isEqualTo(204);
  }

  @Test
  void onlyThisSessionsFreshSetupCanBeConfirmed() throws Exception {
    UUID owner = mfa.owner("owner@example.test");
    Browser first = mfa.login("owner@example.test");
    Browser second = mfa.login("owner@example.test");
    String firstSecret = mfa.setup(first);
    String firstCode = mfa.code(firstSecret);
    // A concurrent setup between generating the code and confirming it replaces the pending one.
    String secondSecret = mfa.setup(second);
    assertError(mfa.confirm(first, firstCode), 409, "MFA_SETUP_EXPIRED");
    assertError(mfa.confirm(first, mfa.code(secondSecret)), 409, "MFA_SETUP_EXPIRED");
    assertThat(mfa.factorRow(owner).get("confirmed_at")).isNull();
    assertThat(mfa.confirm(second, mfa.code(secondSecret)).statusCode()).isEqualTo(200);
    // Now enrolled: the first session must prove the factor instead.
    assertThat(mfa.state(first)).isEqualTo("MFA_PENDING");
    assertError(first.post("/v1/auth/mfa/totp/setup", true), 401, "MFA_REQUIRED");

    mfa.owner("late@example.test");
    Browser late = mfa.login("late@example.test");
    String lateSecret = mfa.setup(late);
    mfa.superuser.update(
        "UPDATE user_mfa_totp SET pending_created_at = now() - interval '11 minutes'"
            + " WHERE subject_id = (SELECT subject_id FROM user_accounts WHERE email = ?)",
        "late@example.test");
    assertError(mfa.confirm(late, mfa.code(lateSecret)), 409, "MFA_SETUP_EXPIRED");
    assertThat(mfa.state(late)).isEqualTo("ENROLLMENT_REQUIRED");
  }

  @Test
  void aSessionWaitingForTheCodeExpiresAfterFiveMinutes() throws Exception {
    mfa.enrolledOwner("owner@example.test");
    Browser slow = mfa.login("owner@example.test");
    assertThat(mfa.state(slow)).isEqualTo("MFA_PENDING");
    clock.advance(Duration.ofMinutes(5).plusSeconds(1));
    assertError(slow.get("/v1/auth/me"), 401, "SESSION_REVOKED");
    assertError(slow.get("/v1/auth/me"), 401, "AUTHENTICATION_REQUIRED");

    Browser timely = mfa.login("owner@example.test");
    clock.advance(Duration.ofMinutes(4));
    assertThat(mfa.verify(timely, mfa.fixtureCode()).statusCode()).isEqualTo(204);
    // Once proved, the session no longer depends on the login instant.
    clock.advance(Duration.ofMinutes(10));
    assertThat(timely.get("/v1/auth/clinics").statusCode()).isEqualTo(200);
  }

  static void assertNoStore(java.net.http.HttpResponse<String> response) {
    assertThat(response.headers().allValues("Cache-Control"))
        .as("secrets are never cached")
        .anySatisfy(value -> assertThat(value).contains("no-store"));
  }
}
