package br.com.branlycrm.identity.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.branlycrm.identity.mfa.crypto.Totp;
import br.com.branlycrm.identity.mfa.domain.SealedSecret;
import br.com.branlycrm.support.Browser;
import br.com.branlycrm.support.MfaFixture;
import br.com.branlycrm.support.MutableClock;
import br.com.branlycrm.support.TestPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Shared steps of the second-factor ITs: synthetic fixtures written as the container superuser
 * while the application runs as crm_control_runtime, real HTTP sessions, and TOTP codes computed
 * from the application clock as an authenticator app would. Only @example.test addresses.
 */
final class MfaSupport {
  static final String PASSWORD = "Synthetic mfa integration secret 2026!";

  final JdbcTemplate superuser;
  private final PasswordEncoder encoder;
  private final Path mail;
  private final int port;
  private final MutableClock clock;

  MfaSupport(
      PostgreSQLContainer postgres,
      PasswordEncoder encoder,
      Path mail,
      int port,
      MutableClock clock) {
    this.superuser =
        new JdbcTemplate(
            new DriverManagerDataSource(
                TestPostgres.url(postgres, postgres.getDatabaseName()),
                postgres.getUsername(),
                postgres.getPassword()));
    this.encoder = encoder;
    this.mail = mail;
    this.port = port;
    this.clock = clock;
  }

  /** Each class owns a disposable container; never clean the developer database. */
  void reset() {
    // Suspend first: the V6 trigger forbids removing the last owner of an ACTIVE clinic.
    superuser.update("UPDATE tenants SET status = 'SUSPENDED' WHERE status = 'ACTIVE'");
    superuser.update("DELETE FROM invitations");
    superuser.update("DELETE FROM tenant_provisioning_tasks");
    superuser.update("DELETE FROM tenant_memberships");
    superuser.update("DELETE FROM agency_memberships");
    superuser.update("DELETE FROM tenants");
    superuser.update("DELETE FROM agencies");
    superuser.update("DELETE FROM account_notices");
    superuser.update("DELETE FROM user_mfa_recovery_codes");
    superuser.update("DELETE FROM user_mfa_totp");
    superuser.update("DELETE FROM user_accounts");
    try (var files = Files.list(mail)) {
      for (Path file : files.toList()) Files.delete(file);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  UUID account(String email) {
    UUID id = UUID.randomUUID();
    superuser.update(
        "INSERT INTO user_accounts (subject_id, email, display_name, password_hash, email_verified)"
            + " VALUES (?, ?, 'Synthetic user', ?, true)",
        id,
        email,
        encoder.encode(PASSWORD));
    return id;
  }

  UUID clinic(String name) {
    UUID id = UUID.randomUUID();
    String suffix = id.toString().replace("-", "");
    superuser.update(
        "INSERT INTO tenants (tenant_id, clinic_name, database_name, database_user,"
            + " credential_reference, status) VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
        id,
        name,
        "clinic_" + suffix,
        "tenant_" + suffix,
        "TENANT_SECRET_" + suffix.toUpperCase(Locale.ROOT));
    return id;
  }

  void member(UUID tenant, UUID subject, String role) {
    superuser.update(
        "INSERT INTO tenant_memberships (tenant_id, subject_id, role) VALUES (?, ?, ?)",
        tenant,
        subject,
        role);
  }

  /** An owner of a fresh clinic, without a factor. */
  UUID owner(String email) {
    UUID subject = account(email);
    member(clinic("Clínica de " + email), subject, "OWNER");
    return subject;
  }

  /** An owner with the fixture factor ({@link MfaFixture#SECRET}). */
  UUID enrolledOwner(String email) {
    UUID subject = owner(email);
    MfaFixture.enroll(superuser, subject);
    return subject;
  }

  UUID agency() {
    return superuser.queryForObject(
        "INSERT INTO agencies (name) VALUES ('Agência sintética') RETURNING agency_id", UUID.class);
  }

  void agencyAdmin(UUID agency, UUID subject) {
    superuser.update(
        "INSERT INTO agency_memberships (agency_id, subject_id, role) VALUES (?, ?, 'AGENCY_ADMIN')",
        agency,
        subject);
  }

  Browser anonymous() throws Exception {
    Browser browser = new Browser(port);
    browser.csrf();
    return browser;
  }

  /** Password login only: the session is in whatever second-factor state the account implies. */
  Browser login(String email) throws Exception {
    Browser browser = anonymous();
    login(browser, email);
    return browser;
  }

  void login(Browser browser, String email) throws Exception {
    assertThat(browser.login(email, PASSWORD).statusCode()).as("login " + email).isEqualTo(204);
    browser.csrf();
  }

  JsonNode status(Browser browser) throws Exception {
    var response = browser.get("/v1/auth/mfa");
    assertThat(response.statusCode()).isEqualTo(200);
    return Browser.JSON.readTree(response.body());
  }

  String state(Browser browser) throws Exception {
    return status(browser).get("state").asText();
  }

  /** Starts a setup and returns the base32 secret. */
  String setup(Browser browser) throws Exception {
    var response = browser.post("/v1/auth/mfa/totp/setup", true);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return Browser.JSON.readTree(response.body()).get("secret").asText();
  }

  HttpResponse<String> confirm(Browser browser, String code) throws Exception {
    return browser.postJson("/v1/auth/mfa/totp/confirm", codeBody(code), true);
  }

  /** Setup and confirmation; returns the recovery codes. */
  List<String> enroll(Browser browser, String[] secretHolder) throws Exception {
    String secret = setup(browser);
    var confirmed = confirm(browser, code(secret));
    assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
    secretHolder[0] = secret;
    return recoveryCodes(confirmed);
  }

  static List<String> recoveryCodes(HttpResponse<String> response) {
    List<String> codes = new ArrayList<>();
    Browser.JSON.readTree(response.body()).get("recoveryCodes").forEach(c -> codes.add(c.asText()));
    return codes;
  }

  HttpResponse<String> verify(Browser browser, String code) throws Exception {
    return browser.postJson("/v1/auth/mfa/verify", codeBody(code), true);
  }

  HttpResponse<String> verifyRecovery(Browser browser, String recoveryCode) throws Exception {
    return browser.postJson(
        "/v1/auth/mfa/verify", "{\"recoveryCode\":\"" + recoveryCode + "\"}", true);
  }

  /** Current code of a base32 secret on the application clock. */
  String code(String base32Secret) {
    return MfaFixture.code(base32Secret, clock.instant());
  }

  /** Current code of the fixture factor. */
  String fixtureCode() {
    return MfaFixture.code(MfaFixture.SECRET, clock.instant());
  }

  /** Six digits that match no step of the window around now. */
  static String wrongCode(byte[] secret, MutableClock clock) {
    long step = Totp.step(clock.instant());
    for (int candidate = 0; ; candidate++) {
      String code = String.format(Locale.ROOT, "%06d", candidate);
      if (Totp.match(secret, code, step).isEmpty()) return code;
    }
  }

  String wrongFixtureCode() {
    return wrongCode(MfaFixture.SECRET, clock);
  }

  static String codeBody(String code) {
    return "{\"code\":\"" + code + "\"}";
  }

  static String code(HttpResponse<String> response) {
    return Browser.JSON.readTree(response.body()).get("code").asText();
  }

  static void assertError(HttpResponse<String> response, int status, String code) {
    String where = response.request().method() + " " + response.uri().getPath();
    assertThat(response.statusCode()).as(where + " " + response.body()).isEqualTo(status);
    assertThat(code(response)).as(where).isEqualTo(code);
  }

  /** The confirmed and pending secrets as stored. */
  Map<String, Object> factorRow(UUID subject) {
    return superuser.queryForMap(
        "SELECT key_id, secret_ciphertext, secret_nonce, confirmed_at, pending_key_id,"
            + " pending_ciphertext, pending_nonce, last_used_step, failed_attempts, locked_until"
            + " FROM user_mfa_totp WHERE subject_id = ?",
        subject);
  }

  static SealedSecret pending(Map<String, Object> row) {
    return new SealedSecret(
        ((Number) row.get("pending_key_id")).intValue(),
        (byte[]) row.get("pending_ciphertext"),
        (byte[]) row.get("pending_nonce"));
  }

  static SealedSecret confirmed(Map<String, Object> row) {
    return new SealedSecret(
        ((Number) row.get("key_id")).intValue(),
        (byte[]) row.get("secret_ciphertext"),
        (byte[]) row.get("secret_nonce"));
  }

  List<byte[]> recoveryHashes(UUID subject) {
    return superuser.query(
        "SELECT code_hash FROM user_mfa_recovery_codes WHERE subject_id = ?",
        (row, index) -> row.getBytes("code_hash"),
        subject);
  }

  /** Subjects of the e-mails the local adapter wrote, oldest first. */
  List<String> mailSubjects() {
    try (var files = Files.list(mail)) {
      List<String> subjects = new ArrayList<>();
      for (Path file : files.sorted().toList()) {
        Files.readString(file)
            .lines()
            .filter(line -> line.startsWith("Subject: "))
            .findFirst()
            .ifPresent(line -> subjects.add(line.substring("Subject: ".length())));
      }
      return subjects;
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  /** Every e-mail text, to prove no secret or code ever leaves by mail. */
  String allMail() {
    try (var files = Files.list(mail)) {
      StringBuilder all = new StringBuilder();
      for (Path file : files.toList()) all.append(Files.readString(file));
      return all.toString();
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  static Path temporaryDirectory() {
    try {
      return Files.createTempDirectory("branly-mfa-mail");
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
