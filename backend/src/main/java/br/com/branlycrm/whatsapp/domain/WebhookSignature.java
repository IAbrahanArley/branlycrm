package br.com.branlycrm.whatsapp.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@code X-Hub-Signature-256} check (ADR-036): HMAC-SHA256 of the raw body with the app secret,
 * compared in constant time, before the body is parsed.
 */
public final class WebhookSignature {
  private static final String PREFIX = "sha256=";
  private static final int HEX_LENGTH = 64;

  private WebhookSignature() {}

  /** True only for a well-formed header that matches the body. */
  public static boolean matches(byte[] appSecret, byte[] body, String header) {
    if (appSecret == null || appSecret.length == 0 || body == null || header == null) return false;
    if (header.length() != PREFIX.length() + HEX_LENGTH || !header.startsWith(PREFIX)) {
      return false;
    }
    byte[] received;
    try {
      received = HexFormat.of().parseHex(header.substring(PREFIX.length()));
    } catch (IllegalArgumentException malformed) {
      return false;
    }
    return MessageDigest.isEqual(hmac(appSecret, body), received);
  }

  /** The header value for a body, as Meta computes it. */
  public static String sign(byte[] appSecret, byte[] body) {
    return PREFIX + HexFormat.of().formatHex(hmac(appSecret, body));
  }

  /** Constant-time comparison of two texts, such as the verification token. */
  public static boolean sameText(String expected, String received) {
    if (expected == null || expected.isEmpty() || received == null) return false;
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), received.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] hmac(byte[] key, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(body);
    } catch (GeneralSecurityException missing) {
      throw new IllegalStateException("HmacSHA256 unavailable", missing);
    }
  }
}
