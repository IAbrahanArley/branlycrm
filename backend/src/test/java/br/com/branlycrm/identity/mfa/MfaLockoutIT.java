package br.com.branlycrm.identity.mfa;

import static br.com.branlycrm.identity.mfa.MfaSupport.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.identity.mail.PublicOrigin;
import br.com.branlycrm.platform.repositories.PlatformRepository;
import br.com.branlycrm.platform.services.PlatformCommand;
import br.com.branlycrm.support.Browser;
import br.com.branlycrm.support.MfaFixture;
import br.com.branlycrm.support.MutableClock;
import br.com.branlycrm.support.MutableClockConfiguration;
import br.com.branlycrm.support.TestPostgres;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
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

/**
 * Wrong codes and platform recovery (CRM-016): five per session end the session, ten per account
 * lock it, and the reset-mfa command run as crm_platform_operator removes the factor and ends every
 * session without the operator reading any second-factor data. Stays below 20 logins.
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
class MfaLockoutIT {
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
    // Each test starts in a fresh window of the request limiter (application clock).
    clock.advance(Duration.ofSeconds(61));
  }

  @Test
  void theFifthWrongCodeOfASessionEndsIt() throws Exception {
    mfa.enrolledOwner("owner@example.test");
    Browser browser = mfa.login("owner@example.test");
    for (int i = 1; i <= 4; i++) {
      assertError(mfa.verify(browser, mfa.wrongFixtureCode()), 400, "MFA_CODE_INVALID");
    }
    assertError(mfa.verify(browser, mfa.wrongFixtureCode()), 401, "SESSION_REVOKED");
    assertError(browser.get("/v1/auth/me"), 401, "AUTHENTICATION_REQUIRED");
  }

  @Test
  void wrongCodesAcrossLoginsLockTheAccountLongerEachTimeAndWarnOnce() throws Exception {
    UUID owner = mfa.enrolledOwner("owner@example.test");
    Browser browser = nineWrongCodes("owner@example.test");
    var locked = mfa.verify(browser, mfa.wrongFixtureCode());
    assertError(locked, 429, "MFA_LOCKED");
    assertThat(retryAfter(locked)).isBetween(1L, 900L);
    // Locked: even the right code and recovery codes are refused, with the same answer.
    var right = mfa.verify(browser, mfa.fixtureCode());
    assertError(right, 429, "MFA_LOCKED");
    assertThat(right.headers().firstValue("Retry-After")).isPresent();
    assertError(mfa.verifyRecovery(browser, "AAAA-BBBB-CCCC-DDDD"), 429, "MFA_LOCKED");
    assertThat(
            mfa.superuser.queryForObject(
                "SELECT locked_until > now() + interval '14 minutes' FROM user_mfa_totp"
                    + " WHERE subject_id = ?",
                Boolean.class,
                owner))
        .isTrue();
    assertThat(mfa.mailSubjects())
        .as("one notice per lock window")
        .containsExactly("Segundo fator bloqueado");

    // The lock ends; ten more wrong codes lock it again for twice as long.
    expireLock(owner);
    nextLimiterWindow();
    Browser again = nineWrongCodes("owner@example.test");
    var longer = mfa.verify(again, mfa.wrongFixtureCode());
    assertError(longer, 429, "MFA_LOCKED");
    assertThat(retryAfter(longer)).isGreaterThan(900L).isLessThanOrEqualTo(1800L);
    assertThat(mfa.mailSubjects())
        .containsExactly("Segundo fator bloqueado", "Segundo fator bloqueado");

    // When the lock ends the right code works again, and a success clears every counter.
    expireLock(owner);
    assertThat(mfa.verify(again, mfa.fixtureCode()).statusCode()).isEqualTo(204);
    assertThat(
            mfa.superuser.queryForMap(
                "SELECT failed_attempts, consecutive_locks FROM user_mfa_totp"
                    + " WHERE subject_id = ?",
                owner))
        .containsEntry("failed_attempts", 0)
        .containsEntry("consecutive_locks", 0);
  }

  @Test
  void aParallelBurstComparesAtMostTenWrongCodes() throws Exception {
    UUID owner = mfa.enrolledOwner("owner@example.test");
    // Four requests per session: the per-session limit (five) never ends a session here.
    List<Browser> sessions = new ArrayList<>();
    for (int i = 0; i < 4; i++) sessions.add(mfa.login("owner@example.test"));
    String wrong = mfa.wrongFixtureCode();
    int requests = sessions.size() * 4;
    CyclicBarrier start = new CyclicBarrier(requests);
    List<Integer> statuses = new ArrayList<>();
    try (var pool = Executors.newFixedThreadPool(requests)) {
      List<Future<HttpResponse<String>>> answers = new ArrayList<>();
      for (Browser session : sessions) {
        for (int i = 0; i < 4; i++) {
          answers.add(
              pool.submit(
                  () -> {
                    start.await(10, TimeUnit.SECONDS);
                    return mfa.verify(session, wrong);
                  }));
        }
      }
      for (var answer : answers) {
        HttpResponse<String> response = answer.get(30, TimeUnit.SECONDS);
        statuses.add(response.statusCode());
        if (response.statusCode() == 400) assertError(response, 400, "MFA_CODE_INVALID");
        if (response.statusCode() == 429) assertError(response, 429, "MFA_LOCKED");
      }
    }
    assertThat(statuses).containsOnly(400, 429).contains(429);
    // The tenth compared failure starts the lock, so at most nine answers are plain refusals.
    assertThat(statuses.stream().filter(status -> status == 400).count()).isLessThanOrEqualTo(9);
    assertThat(
            mfa.superuser.queryForMap(
                "SELECT locked_until > now() AS locked, consecutive_locks FROM user_mfa_totp"
                    + " WHERE subject_id = ?",
                owner))
        .containsEntry("locked", true)
        .containsEntry("consecutive_locks", 1);
    assertThat(mfa.mailSubjects()).containsExactly("Segundo fator bloqueado");
  }

  /** Nine wrong codes over three logins (4, 4, 1); returns the last session, still pending. */
  private Browser nineWrongCodes(String email) throws Exception {
    Browser browser = null;
    int failures = 0;
    while (failures < 9) {
      browser = mfa.login(email);
      for (int i = 0; i < 4 && failures < 9; i++, failures++) {
        assertError(mfa.verify(browser, mfa.wrongFixtureCode()), 400, "MFA_CODE_INVALID");
      }
    }
    return browser;
  }

  private void expireLock(UUID owner) {
    mfa.superuser.update(
        "UPDATE user_mfa_totp SET locked_until = now() - interval '1 second' WHERE subject_id = ?",
        owner);
  }

  /** The request limiter counts per minute on the application clock. */
  private void nextLimiterWindow() {
    clock.advance(Duration.ofSeconds(61));
  }

  private static long retryAfter(HttpResponse<String> response) {
    return Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow());
  }

  @Test
  void platformResetRemovesTheFactorAndEndsEverySession() throws Exception {
    UUID owner = mfa.enrolledOwner("owner@example.test");
    Browser first = mfa.login("owner@example.test");
    assertThat(mfa.verify(first, mfa.fixtureCode()).statusCode()).isEqualTo(204);
    Browser second = mfa.login("owner@example.test");
    clock.nextStep();
    assertThat(mfa.verify(second, mfa.fixtureCode()).statusCode()).isEqualTo(204);
    mfa.superuser.update(
        "INSERT INTO user_mfa_recovery_codes (subject_id, code_hash) VALUES (?, ?)",
        owner,
        MfaFixture.CRYPTO.hashRecoveryCode(owner, "AAAA-BBBB-CCCC-DDDD"));

    String output = platform("reset-mfa", "--email", " Owner@Example.test ");
    assertThat(output).contains("mfaFactorRemoved: true").doesNotContainIgnoringCase("example");
    assertError(first.get("/v1/auth/me"), 401, "SESSION_REVOKED");
    assertError(second.get("/v1/auth/me"), 401, "SESSION_REVOKED");
    for (String table : new String[] {"user_mfa_totp", "user_mfa_recovery_codes"}) {
      assertThat(
              mfa.superuser.queryForObject(
                  "SELECT count(*) FROM " + table + " WHERE subject_id = ?", Long.class, owner))
          .as(table)
          .isZero();
    }
    assertThat(platform("reset-mfa", "--email", "owner@example.test"))
        .contains("mfaFactorRemoved: false");
    assertThat(platform("reset-mfa", "--email", "nobody@example.test"))
        .contains("mfaFactorRemoved: false");
    assertThatThrownBy(() -> platform("reset-mfa")).isInstanceOf(IllegalArgumentException.class);

    // The platform has no mail: the notices wait in the database for the next login.
    assertThat(mfa.mailSubjects()).isEmpty();
    assertThat(pendingNotices(owner)).isEqualTo(2);
    Browser again = mfa.login("owner@example.test");
    assertThat(mfa.state(again)).isEqualTo("ENROLLMENT_REQUIRED");
    assertThat(mfa.mailSubjects())
        .containsExactly("Segundo fator redefinido", "Segundo fator redefinido");
    assertThat(mfa.allMail()).doesNotContain("AAAA-BBBB");
    assertThat(pendingNotices(owner)).isZero();
    mfa.login("owner@example.test");
    assertThat(mfa.mailSubjects()).as("sent once").hasSize(2);
  }

  private long pendingNotices(UUID subject) {
    return mfa.superuser.queryForObject(
        "SELECT count(*) FROM account_notices WHERE subject_id = ? AND kind = 'MFA_RESET'"
            + " AND sent_at IS NULL",
        Long.class,
        subject);
  }

  @Test
  void theOperatorCallsTheResetButReadsAndWritesNoSecondFactorData() {
    JdbcTemplate operator = new JdbcTemplate(operatorSource());
    for (String forbidden :
        new String[] {
          "SELECT count(*) FROM user_mfa_totp",
          "SELECT count(*) FROM user_mfa_recovery_codes",
          "SELECT count(*) FROM account_notices",
          "INSERT INTO account_notices (subject_id, kind)"
              + " SELECT gen_random_uuid(), 'MFA_RESET'",
          "SELECT account_notice_claim(gen_random_uuid(), gen_random_uuid())",
          "DELETE FROM user_mfa_totp",
          "SELECT mfa_disable(gen_random_uuid())",
          "SELECT mfa_reset_failures(gen_random_uuid())"
        }) {
      assertThatThrownBy(() -> operator.execute(forbidden))
          .as(forbidden)
          .hasRootCauseInstanceOf(SQLException.class)
          .rootCause()
          .extracting(cause -> ((SQLException) cause).getSQLState())
          .isEqualTo("42501");
    }
    assertThat(
            operator.queryForObject(
                "SELECT platform_reset_mfa('nobody@example.test')", Boolean.class))
        .isFalse();
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

  private static DriverManagerDataSource operatorSource() {
    return new DriverManagerDataSource(
        TestPostgres.url(POSTGRES, POSTGRES.getDatabaseName()),
        TestPostgres.PLATFORM,
        TestPostgres.PASSWORD);
  }
}
