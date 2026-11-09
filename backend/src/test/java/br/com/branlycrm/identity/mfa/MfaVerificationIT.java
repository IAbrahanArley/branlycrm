package br.com.branlycrm.identity.mfa;

import static br.com.branlycrm.identity.mfa.MfaSupport.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.branlycrm.config.SecurityConfiguration;
import br.com.branlycrm.identity.mfa.domain.MfaState;
import br.com.branlycrm.identity.security.MfaGateFilter;
import br.com.branlycrm.identity.security.SessionValidationFilter;
import br.com.branlycrm.support.Browser;
import br.com.branlycrm.support.MfaFixture;
import br.com.branlycrm.support.MutableClock;
import br.com.branlycrm.support.MutableClockConfiguration;
import br.com.branlycrm.support.TestPostgres;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proving the factor (CRM-016): the deny-by-default gate over every mapped route, session switching
 * on one cookie, session fixation, replay, concurrency and recovery codes. Real HTTP, application
 * as crm_control_runtime, clock moved by the test. Stays below 20 logins.
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
class MfaVerificationIT {
  @Container static final PostgreSQLContainer POSTGRES = TestPostgres.container();
  static final Path MAIL = MfaSupport.temporaryDirectory();

  @LocalServerPort int port;
  @Autowired PasswordEncoder encoder;
  @Autowired MutableClock clock;

  @Autowired SecurityFilterChain filterChain;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping mappings;

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
  void aPendingSessionReachesNothingOutsideTheAllowList() throws Exception {
    mfa.enrolledOwner("owner@example.test");
    Browser pending = mfa.login("owner@example.test");
    String uuid = UUID.randomUUID().toString();
    List<String> swept = new ArrayList<>();
    for (var info : mappings.getHandlerMethods().keySet()) {
      Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
      for (String pattern : info.getPatternValues()) {
        String path = pattern.replaceAll("\\{[^}]*}", uuid).replace("**", "x");
        for (RequestMethod method : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
          if (MfaGateFilter.allows(MfaState.MFA_PENDING, method.name(), path)) continue;
          HttpResponse<String> response =
              method == RequestMethod.GET
                  ? pending.get(path)
                  : pending.send(method.name(), path, "{}");
          swept.add(method + " " + path);
          assertError(response, 401, "MFA_REQUIRED");
        }
      }
    }
    assertThat(swept)
        .as("every business route was tried")
        .contains(
            "GET /v1/auth/clinics",
            "GET /v1/clinic/team",
            "GET /v1/agencies",
            "POST /v1/invitations/accept",
            "POST /v1/auth/mfa/totp/setup",
            "DELETE /v1/auth/mfa/totp",
            "GET /v3/api-docs")
        .hasSizeGreaterThan(15);
    assertError(pending.get("/v1/does-not-exist"), 401, "MFA_REQUIRED");
    assertThat(pending.get("/actuator/health").statusCode()).isEqualTo(200);
    // The gate refuses without ending the session: the code still works.
    assertThat(mfa.verify(pending, mfa.fixtureCode()).statusCode()).isEqualTo(204);
    assertThat(pending.get("/v1/auth/clinics").statusCode()).isEqualTo(200);
  }

  @Test
  void aSessionWithoutTheMandatoryFactorReachesOnlyEnrollment() throws Exception {
    mfa.owner("owner@example.test");
    Browser enrolling = mfa.login("owner@example.test");
    assertThat(mfa.state(enrolling)).isEqualTo("ENROLLMENT_REQUIRED");
    List<String> swept =
        sweep(enrolling, MfaState.ENROLLMENT_REQUIRED, 403, "MFA_ENROLLMENT_REQUIRED");
    assertThat(swept)
        .contains(
            "GET /v1/auth/clinics",
            "GET /v1/agencies",
            "POST /v1/invitations/accept",
            "POST /v1/auth/mfa/verify",
            "POST /v1/auth/mfa/recovery-codes",
            "DELETE /v1/auth/mfa/totp")
        .doesNotContain("POST /v1/auth/mfa/totp/setup", "POST /v1/auth/mfa/totp/confirm")
        .hasSizeGreaterThan(15);
    assertThat(mfa.setup(enrolling)).isNotBlank();
  }

  /** The gate runs right after the session validation and before authorization. */
  @Test
  void theGateSitsBetweenSessionValidationAndAuthorization() {
    List<Class<?>> order =
        filterChain.getFilters().stream().<Class<?>>map(Object::getClass).toList();
    int validation = order.indexOf(SessionValidationFilter.class);
    int gate = order.indexOf(MfaGateFilter.class);
    int authorization = order.indexOf(AuthorizationFilter.class);
    assertThat(validation).isNotNegative();
    assertThat(gate).isEqualTo(validation + 1);
    assertThat(authorization).isEqualTo(gate + 1);
  }

  /** Every mapped route not allowed in this state answers the given error. */
  private List<String> sweep(Browser browser, MfaState state, int status, String code)
      throws Exception {
    String uuid = UUID.randomUUID().toString();
    List<String> swept = new ArrayList<>();
    for (var info : mappings.getHandlerMethods().keySet()) {
      Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
      for (String pattern : info.getPatternValues()) {
        String path = pattern.replaceAll("\\{[^}]*}", uuid).replace("**", "x");
        for (RequestMethod method : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
          if (MfaGateFilter.allows(state, method.name(), path)) continue;
          HttpResponse<String> response =
              method == RequestMethod.GET
                  ? browser.get(path)
                  : browser.send(method.name(), path, "{}");
          swept.add(method + " " + path);
          assertError(response, status, code);
        }
      }
    }
    assertError(browser.get("/v1/does-not-exist"), status, code);
    assertThat(browser.get("/actuator/health").statusCode()).isEqualTo(200);
    return swept;
  }

  @Test
  void anotherLoginOnTheSameCookieNeverInheritsTheProof() throws Exception {
    UUID first = mfa.enrolledOwner("first@example.test");
    UUID second = mfa.enrolledOwner("second@example.test");
    Browser browser = mfa.login("first@example.test");
    assertThat(mfa.verify(browser, mfa.fixtureCode()).statusCode()).isEqualTo(204);
    assertThat(browser.get("/v1/auth/clinics").statusCode()).isEqualTo(200);

    mfa.login(browser, "second@example.test");
    assertThat(subject(browser)).isEqualTo(second);
    assertThat(mfa.state(browser)).isEqualTo("MFA_PENDING");
    assertError(browser.get("/v1/auth/clinics"), 401, "MFA_REQUIRED");

    mfa.login(browser, "first@example.test");
    assertThat(subject(browser)).isEqualTo(first);
    assertThat(mfa.state(browser)).as("a new login asks again").isEqualTo("MFA_PENDING");
    assertError(browser.get("/v1/auth/clinics"), 401, "MFA_REQUIRED");
    clock.nextStep();
    assertThat(mfa.verify(browser, mfa.fixtureCode()).statusCode()).isEqualTo(204);
    assertThat(browser.get("/v1/auth/clinics").statusCode()).isEqualTo(200);
  }

  @Test
  void provingTheFactorRenewsTheSessionId() throws Exception {
    mfa.enrolledOwner("owner@example.test");
    Browser browser = mfa.login("owner@example.test");
    String before = browser.cookie(SecurityConfiguration.SESSION_COOKIE);
    assertThat(before).isNotBlank();
    assertThat(mfa.verify(browser, mfa.fixtureCode()).statusCode()).isEqualTo(204);
    String after = browser.cookie(SecurityConfiguration.SESSION_COOKIE);
    assertThat(after).isNotBlank().isNotEqualTo(before);
    assertThat(browser.get("/v1/auth/clinics").statusCode()).isEqualTo(200);
  }

  @Test
  void aCodeIsAcceptedOnceEvenUnderConcurrency() throws Exception {
    mfa.enrolledOwner("owner@example.test");
    Browser first = mfa.login("owner@example.test");
    String code = mfa.fixtureCode();
    assertThat(mfa.verify(first, code).statusCode()).isEqualTo(204);
    Browser replay = mfa.login("owner@example.test");
    assertError(mfa.verify(replay, code), 400, "MFA_CODE_INVALID");

    clock.nextStep();
    String fresh = mfa.fixtureCode();
    List<Browser> racers =
        List.of(mfa.login("owner@example.test"), mfa.login("owner@example.test"));
    List<Integer> statuses = race(racers, browser -> mfa.verify(browser, fresh));
    assertThat(statuses).containsExactlyInAnyOrder(204, 400);
  }

  @Test
  void recoveryCodesAreSingleUseHashedAndReplaceable() throws Exception {
    UUID owner = mfa.owner("owner@example.test");
    Browser enrolling = mfa.login("owner@example.test");
    String[] secret = new String[1];
    List<String> codes = mfa.enroll(enrolling, secret);
    for (byte[] hash : mfa.recoveryHashes(owner)) {
      for (String code : codes) {
        assertThat(new String(hash, StandardCharsets.ISO_8859_1)).doesNotContain(code);
        assertThat(hash).isNotEqualTo(code.getBytes(StandardCharsets.US_ASCII));
      }
    }

    Browser recovering = mfa.login("owner@example.test");
    // Typed in lower case and without dashes: the same code.
    String typed = codes.get(0).toLowerCase(java.util.Locale.ROOT).replace("-", "");
    assertThat(mfa.verifyRecovery(recovering, typed).statusCode()).isEqualTo(204);
    assertThat(mfa.status(recovering).get("recoveryCodesRemaining").asLong()).isEqualTo(9);
    assertThat(mfa.mailSubjects()).contains("Código de recuperação usado");
    assertThat(mfa.allMail()).doesNotContain(codes.get(0));

    Browser reused = mfa.login("owner@example.test");
    assertError(mfa.verifyRecovery(reused, codes.get(0)), 400, "MFA_CODE_INVALID");
    assertError(mfa.verifyRecovery(reused, "AAAA-BBBB-CCCC-DDDD"), 400, "MFA_CODE_INVALID");
    assertError(mfa.verifyRecovery(reused, "not-a-code"), 400, "INVALID_INPUT");

    List<Browser> racers =
        List.of(mfa.login("owner@example.test"), mfa.login("owner@example.test"));
    String shared = codes.get(1);
    List<Integer> statuses = race(racers, browser -> mfa.verifyRecovery(browser, shared));
    assertThat(statuses).containsExactlyInAnyOrder(204, 400);
    Browser complete = statuses.get(0) == 204 ? racers.get(0) : racers.get(1);

    clock.nextStep();
    var regenerated =
        complete.postJson(
            "/v1/auth/mfa/recovery-codes", MfaSupport.codeBody(mfa.code(secret[0])), true);
    assertThat(regenerated.statusCode()).isEqualTo(200);
    MfaEnrollmentIT.assertNoStore(regenerated);
    assertThat(mfa.mailSubjects()).contains("Novos códigos de recuperação");
    List<String> fresh = MfaSupport.recoveryCodes(regenerated);
    assertThat(fresh).hasSize(10).doesNotContainAnyElementsOf(codes);
    assertThat(mfa.status(complete).get("recoveryCodesRemaining").asLong()).isEqualTo(10);
    assertThat(mfa.recoveryHashes(owner)).hasSize(10);

    Browser later = mfa.login("owner@example.test");
    assertError(mfa.verifyRecovery(later, codes.get(2)), 400, "MFA_CODE_INVALID");
    assertThat(mfa.verifyRecovery(later, fresh.get(0)).statusCode()).isEqualTo(204);
    assertThat(
            mfa.recoveryHashes(owner).stream()
                .anyMatch(
                    hash ->
                        Arrays.equals(
                            hash, MfaFixture.CRYPTO.hashRecoveryCode(owner, codes.get(2)))))
        .isFalse();
  }

  private interface Attempt {
    HttpResponse<String> run(Browser browser) throws Exception;
  }

  private static List<Integer> race(List<Browser> browsers, Attempt attempt) throws Exception {
    CyclicBarrier start = new CyclicBarrier(browsers.size());
    try (var pool = Executors.newFixedThreadPool(browsers.size())) {
      List<Future<HttpResponse<String>>> answers = new ArrayList<>();
      for (Browser browser : browsers) {
        Callable<HttpResponse<String>> task =
            () -> {
              start.await(10, TimeUnit.SECONDS);
              return attempt.run(browser);
            };
        answers.add(pool.submit(task));
      }
      List<Integer> statuses = new ArrayList<>();
      for (var answer : answers) {
        HttpResponse<String> response = answer.get(30, TimeUnit.SECONDS);
        if (response.statusCode() != 204) assertError(response, 400, "MFA_CODE_INVALID");
        statuses.add(response.statusCode());
      }
      return statuses;
    }
  }

  private static UUID subject(Browser browser) throws Exception {
    return UUID.fromString(
        Browser.JSON.readTree(browser.get("/v1/auth/me").body()).get("subjectId").asText());
  }
}
