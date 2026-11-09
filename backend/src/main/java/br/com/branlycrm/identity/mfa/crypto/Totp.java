package br.com.branlycrm.identity.mfa.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 TOTP: HMAC-SHA1, 6 digits, 30-second steps, accepting one step before and after the
 * current one for clock drift. Replay protection (one use per step) lives in the database.
 */
public final class Totp {
  public static final int DIGITS = 6;
  public static final long PERIOD_SECONDS = 30;
  public static final int WINDOW = 1;

  private Totp() {}

  public static long step(Instant at) {
    return Math.floorDiv(at.getEpochSecond(), PERIOD_SECONDS);
  }

  public static String code(byte[] secret, long step) {
    return code(secret, step, DIGITS);
  }

  /** RFC 4226 dynamic truncation; {@code digits} is a parameter for the RFC test vectors. */
  static String code(byte[] secret, long step, int digits) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(secret, "HmacSHA1"));
      byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
      int offset = hash[hash.length - 1] & 0x0F;
      int binary =
          ((hash[offset] & 0x7F) << 24)
              | ((hash[offset + 1] & 0xFF) << 16)
              | ((hash[offset + 2] & 0xFF) << 8)
              | (hash[offset + 3] & 0xFF);
      int modulus = 1;
      for (int i = 0; i < digits; i++) modulus *= 10;
      String value = Integer.toString(binary % modulus);
      return "0".repeat(digits - value.length()) + value;
    } catch (GeneralSecurityException unavailable) {
      throw new IllegalStateException("HmacSHA1 unavailable", unavailable);
    }
  }

  /**
   * The step the code belongs to within the window, comparing every candidate in constant time.
   * Empty when it matches none.
   */
  public static OptionalLong match(byte[] secret, String code, long currentStep) {
    byte[] given = code.getBytes(StandardCharsets.US_ASCII);
    long matched = Long.MIN_VALUE;
    for (long step = currentStep + WINDOW; step >= currentStep - WINDOW; step--) {
      byte[] expected = code(secret, step).getBytes(StandardCharsets.US_ASCII);
      if (MessageDigest.isEqual(expected, given)) matched = step;
    }
    return matched == Long.MIN_VALUE ? OptionalLong.empty() : OptionalLong.of(matched);
  }
}
