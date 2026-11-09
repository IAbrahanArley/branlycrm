package br.com.branlycrm.support;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.branlycrm.identity.mfa.crypto.Base32;
import br.com.branlycrm.identity.mfa.crypto.MfaCrypto;
import br.com.branlycrm.identity.mfa.crypto.Totp;
import br.com.branlycrm.identity.mfa.domain.SealedSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Second factor for integration tests (CRM-016). Owners and agency admins must prove a TOTP code
 * after login; tests that are not about the second factor call {@link #complete} right after
 * logging in. The factor is written as the container superuser with the synthetic key the test
 * context uses, and proved through the real verify endpoint, so the gate is never bypassed.
 */
public final class MfaFixture {
  /** Base64 of 32 synthetic bytes; never a deployment key. */
  public static final String KEY = "c3ludGhldGljLW1mYS10ZXN0LWtleS0yMDI2LTAwMDE=";

  /** For {@code @SpringBootTest(properties = ...)}. */
  public static final String KEY_PROPERTY = "app.mfa.encryption-key=" + KEY;

  /** Shared 20-byte synthetic TOTP secret of fixture factors. */
  public static final byte[] SECRET = "synthetic-totp-2026!".getBytes(StandardCharsets.US_ASCII);

  public static final MfaCrypto CRYPTO = MfaCrypto.fromBase64(KEY);

  private MfaFixture() {}

  /** A confirmed factor with {@link #SECRET}, sealed for the subject like the API does. */
  public static void enroll(JdbcTemplate superuser, UUID subject) {
    SealedSecret sealed = CRYPTO.seal(subject, SECRET);
    superuser.update(
        "INSERT INTO user_mfa_totp (subject_id, key_id, secret_ciphertext, secret_nonce,"
            + " confirmed_at) VALUES (?, ?, ?, ?, now())",
        subject,
        sealed.keyId(),
        sealed.ciphertext(),
        sealed.nonce());
  }

  /**
   * Brings a freshly logged-in session to COMPLETE: nothing for optional accounts without factor;
   * otherwise enrolls the fixture factor when missing and verifies a current code. The used step is
   * cleared first, so the same account can log in several times within 30 seconds.
   */
  public static void complete(Browser browser, JdbcTemplate superuser) throws Exception {
    var status = browser.get("/v1/auth/mfa");
    assertThat(status.statusCode()).as("MFA state").isEqualTo(200);
    JsonNode body = Browser.JSON.readTree(status.body());
    if ("COMPLETE".equals(body.get("state").asText())) return;
    UUID subject =
        UUID.fromString(
            Browser.JSON.readTree(browser.get("/v1/auth/me").body()).get("subjectId").asText());
    if (!body.get("enrolled").asBoolean()) enroll(superuser, subject);
    superuser.update(
        "UPDATE user_mfa_totp SET last_used_step = NULL, failed_attempts = 0, locked_until = NULL"
            + " WHERE subject_id = ?",
        subject);
    browser.csrf();
    var verified =
        browser.postJson(
            "/v1/auth/mfa/verify", "{\"code\":\"" + code(SECRET, Instant.now()) + "\"}", true);
    assertThat(verified.statusCode()).as("MFA verify: " + verified.body()).isEqualTo(204);
  }

  public static String code(byte[] secret, Instant at) {
    return Totp.code(secret, Totp.step(at));
  }

  /** Code for the base32 secret a setup returned, as an authenticator app would compute it. */
  public static String code(String base32Secret, Instant at) {
    return code(Base32.decode(base32Secret), at);
  }
}
