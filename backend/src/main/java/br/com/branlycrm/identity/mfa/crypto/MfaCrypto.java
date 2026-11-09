package br.com.branlycrm.identity.mfa.crypto;

import br.com.branlycrm.identity.mfa.domain.SealedSecret;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Key material of the second factor, derived with HKDF-SHA256 from {@code app.mfa.encryption-key}
 * (32 bytes, base64): one AES-256-GCM key for TOTP secrets and one HMAC-SHA-256 key for recovery
 * codes. The database only ever sees ciphertexts and HMACs. Lives only in the HTTP process; neither
 * the keys nor anything they protect is ever logged or shown by {@link #toString()}.
 */
public final class MfaCrypto {
  /** Current key; rotation will add ids and keep the old ones for opening. */
  public static final int KEY_ID = 1;

  public static final int SECRET_BYTES = 20;
  public static final int RECOVERY_CODE_COUNT = 10;

  /** 16 base32 characters: 80 bits per recovery code. */
  public static final int RECOVERY_CODE_LENGTH = 16;

  private static final byte[] AAD_PREFIX = "branly:mfa:totp:v1".getBytes(StandardCharsets.US_ASCII);
  private static final int TAG_BITS = 128;

  private final SecretKeySpec encryptionKey;
  private final SecretKeySpec recoveryKey;
  private final SecureRandom random = new SecureRandom();

  private MfaCrypto(byte[] masterKey) {
    this.encryptionKey = new SecretKeySpec(hkdf(masterKey, "branly:mfa:totp-encryption"), "AES");
    this.recoveryKey = new SecretKeySpec(hkdf(masterKey, "branly:mfa:recovery"), "HmacSHA256");
  }

  /**
   * @throws IllegalStateException when the key is missing, not base64 or not 32 bytes; the message
   *     never contains the value.
   */
  public static MfaCrypto fromBase64(String encoded) {
    if (encoded == null || encoded.isBlank()) {
      throw new IllegalStateException("MFA_ENCRYPTION_KEY is required (base64 of 32 bytes)");
    }
    byte[] key;
    try {
      key = Base64.getDecoder().decode(encoded.strip());
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException("MFA_ENCRYPTION_KEY must be base64 of 32 bytes");
    }
    if (key.length != 32) {
      throw new IllegalStateException("MFA_ENCRYPTION_KEY must be base64 of 32 bytes");
    }
    try {
      return new MfaCrypto(key);
    } finally {
      java.util.Arrays.fill(key, (byte) 0);
    }
  }

  /** A fresh 160-bit TOTP secret (RFC 4226 recommends at least 128 bits, 160 with SHA-1). */
  public byte[] newSecret() {
    byte[] secret = new byte[SECRET_BYTES];
    random.nextBytes(secret);
    return secret;
  }

  public SealedSecret seal(UUID subject, byte[] secret) {
    if (secret.length != SECRET_BYTES) throw new IllegalArgumentException("Unexpected secret size");
    byte[] nonce = new byte[SealedSecret.NONCE_BYTES];
    random.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad(subject, KEY_ID));
      return new SealedSecret(KEY_ID, cipher.doFinal(secret), nonce);
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("MFA_SEAL_FAILED");
    }
  }

  /**
   * @throws IllegalStateException when the ciphertext, nonce, subject or key id do not match what
   *     was sealed (GCM authentication), or the key id is unknown.
   */
  public byte[] open(UUID subject, SealedSecret sealed) {
    if (sealed.keyId() != KEY_ID) throw new IllegalStateException("MFA_UNKNOWN_KEY");
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, sealed.nonce()));
      cipher.updateAAD(aad(subject, sealed.keyId()));
      return cipher.doFinal(sealed.ciphertext());
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("MFA_OPEN_FAILED");
    }
  }

  /** Ten codes formatted as XXXX-XXXX-XXXX-XXXX, shown once. */
  public List<String> newRecoveryCodes() {
    List<String> codes = new ArrayList<>(RECOVERY_CODE_COUNT);
    for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
      byte[] raw = new byte[RECOVERY_CODE_LENGTH * 5 / 8];
      random.nextBytes(raw);
      String code = Base32.encode(raw);
      codes.add(
          code.substring(0, 4)
              + "-"
              + code.substring(4, 8)
              + "-"
              + code.substring(8, 12)
              + "-"
              + code.substring(12, 16));
    }
    return codes;
  }

  /**
   * Canonical form of a recovery code: separators and spaces removed, upper case; {@code null} when
   * it cannot be a code (wrong length or alphabet).
   */
  public static String normalizeRecoveryCode(String input) {
    if (input == null || input.length() > 64) return null;
    StringBuilder normalized = new StringBuilder(RECOVERY_CODE_LENGTH);
    for (int i = 0; i < input.length(); i++) {
      char value = Character.toUpperCase(input.charAt(i));
      if (value == '-' || value == ' ') continue;
      if (!Base32.isAlphabet(value)) return null;
      normalized.append(value);
    }
    return normalized.length() == RECOVERY_CODE_LENGTH ? normalized.toString() : null;
  }

  /** HMAC-SHA-256 over subject || normalized code; looked up by equality in the database. */
  public byte[] hashRecoveryCode(UUID subject, String code) {
    String normalized = normalizeRecoveryCode(code);
    if (normalized == null) throw new IllegalArgumentException("Not a recovery code");
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(recoveryKey);
      mac.update(uuid(subject));
      return mac.doFinal(normalized.getBytes(StandardCharsets.US_ASCII));
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("MFA_HASH_FAILED");
    }
  }

  @Override
  public String toString() {
    return "MfaCrypto[keyId=" + KEY_ID + "]";
  }

  private static byte[] aad(UUID subject, int keyId) {
    return ByteBuffer.allocate(AAD_PREFIX.length + 16 + Short.BYTES)
        .put(AAD_PREFIX)
        .put(uuid(subject))
        .putShort((short) keyId)
        .array();
  }

  private static byte[] uuid(UUID value) {
    return ByteBuffer.allocate(16)
        .putLong(value.getMostSignificantBits())
        .putLong(value.getLeastSignificantBits())
        .array();
  }

  /** RFC 5869 with a zero salt and one output block (32 bytes). */
  private static byte[] hkdf(byte[] inputKey, String info) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
      byte[] pseudoRandomKey = mac.doFinal(inputKey);
      mac.init(new SecretKeySpec(pseudoRandomKey, "HmacSHA256"));
      mac.update(info.getBytes(StandardCharsets.US_ASCII));
      mac.update((byte) 1);
      return mac.doFinal();
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("MFA_KEY_DERIVATION_FAILED");
    }
  }
}
