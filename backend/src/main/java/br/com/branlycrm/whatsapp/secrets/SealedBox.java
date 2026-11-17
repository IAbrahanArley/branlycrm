package br.com.branlycrm.whatsapp.secrets;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/**
 * Hybrid sealing of channel secrets (ADR-036): a fresh AES-256-GCM key encrypts the value and
 * RSA-OAEP (SHA-256) wraps that key. The context (channel id and purpose) is authenticated data, so
 * a sealed value copied to another channel or field fails to open. Format, all big-endian:
 *
 * <pre>
 * byte 1 (format) | int key version | short wrapped length | wrapped key | 12-byte IV | ciphertext+tag
 * </pre>
 */
final class SealedBox {
  static final byte FORMAT = 1;
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final OAEPParameterSpec OAEP =
      new OAEPParameterSpec(
          "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

  private SealedBox() {}

  static byte[] seal(PublicKey key, int version, String context, byte[] plaintext)
      throws GeneralSecurityException {
    KeyGenerator generator = KeyGenerator.getInstance("AES");
    generator.init(256, RANDOM);
    SecretKey content = generator.generateKey();
    try {
      Cipher wrap = Cipher.getInstance("RSA/ECB/OAEPPadding");
      wrap.init(Cipher.WRAP_MODE, key, OAEP, RANDOM);
      byte[] wrapped = wrap.wrap(content);
      byte[] iv = new byte[IV_BYTES];
      RANDOM.nextBytes(iv);
      Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
      aes.init(Cipher.ENCRYPT_MODE, content, new GCMParameterSpec(TAG_BITS, iv));
      aes.updateAAD(header(version, context));
      byte[] body = aes.doFinal(plaintext);
      return ByteBuffer.allocate(1 + 4 + 2 + wrapped.length + IV_BYTES + body.length)
          .put(FORMAT)
          .putInt(version)
          .putShort((short) wrapped.length)
          .put(wrapped)
          .put(iv)
          .put(body)
          .array();
    } finally {
      destroy(content);
    }
  }

  /** The key version a sealed value needs, read from its header. */
  static int version(byte[] sealed) {
    if (sealed == null || sealed.length < 7 || sealed[0] != FORMAT) {
      throw new IllegalArgumentException("Not a sealed value");
    }
    return ByteBuffer.wrap(sealed, 1, 4).getInt();
  }

  static byte[] open(PrivateKey key, String context, byte[] sealed)
      throws GeneralSecurityException {
    int version = version(sealed);
    ByteBuffer in = ByteBuffer.wrap(sealed);
    in.position(5);
    int wrappedLength = Short.toUnsignedInt(in.getShort());
    if (wrappedLength == 0 || in.remaining() < wrappedLength + IV_BYTES + TAG_BITS / 8) {
      throw new GeneralSecurityException("Truncated sealed value");
    }
    byte[] wrapped = new byte[wrappedLength];
    in.get(wrapped);
    byte[] iv = new byte[IV_BYTES];
    in.get(iv);
    byte[] body = new byte[in.remaining()];
    in.get(body);
    Cipher unwrap = Cipher.getInstance("RSA/ECB/OAEPPadding");
    unwrap.init(Cipher.UNWRAP_MODE, key, OAEP);
    SecretKey content = (SecretKey) unwrap.unwrap(wrapped, "AES", Cipher.SECRET_KEY);
    if (content.getEncoded() == null || content.getEncoded().length != 32) {
      throw new GeneralSecurityException("Unexpected content key size");
    }
    try {
      Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
      aes.init(Cipher.DECRYPT_MODE, content, new GCMParameterSpec(TAG_BITS, iv));
      aes.updateAAD(header(version, context));
      return aes.doFinal(body);
    } finally {
      destroy(content);
    }
  }

  private static byte[] header(int version, String context) {
    if (context == null || context.isBlank()) throw new IllegalArgumentException("No context");
    byte[] text = context.getBytes(StandardCharsets.UTF_8);
    return ByteBuffer.allocate(1 + 4 + text.length).put(FORMAT).putInt(version).put(text).array();
  }

  /** Best effort only: the JDK key keeps its own copy of the bytes. */
  private static void destroy(SecretKey key) {
    if (key instanceof SecretKeySpec spec) {
      byte[] encoded = spec.getEncoded();
      if (encoded != null) Arrays.fill(encoded, (byte) 0);
    }
  }
}
