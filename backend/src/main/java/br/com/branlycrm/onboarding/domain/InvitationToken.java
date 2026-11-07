package br.com.branlycrm.onboarding.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Single-use invitation secret (ADR-028): 32 random bytes in base64url. The database keeps only the
 * SHA-256 of the text, which is enough because the token has full entropy and is looked up by its
 * hash. {@link #toString()} never shows the raw value.
 */
public final class InvitationToken {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9_-]{43}$");

  private final String raw;
  private final byte[] hash;

  private InvitationToken(String raw) {
    this.raw = raw;
    this.hash = sha256(raw);
  }

  public static InvitationToken generate() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return new InvitationToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
  }

  /** Anything not shaped like a token is simply invalid; the caller answers INVITATION_INVALID. */
  public static Optional<InvitationToken> parse(String raw) {
    if (raw == null || !FORMAT.matcher(raw).matches()) return Optional.empty();
    return Optional.of(new InvitationToken(raw));
  }

  /** Only for the e-mail link, sent once. */
  public String raw() {
    return raw;
  }

  public byte[] hash() {
    return Arrays.copyOf(hash, hash.length);
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
    }
  }

  @Override
  public String toString() {
    return "InvitationToken[redacted]";
  }
}
