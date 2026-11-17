package br.com.branlycrm.whatsapp.secrets;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Opens sealed channel secrets with the private keys, old versions included for rotation (ADR-036).
 * Exists only in the WhatsApp worker process: the bean needs {@code app.whatsapp.worker.enabled},
 * the private key directory is mounted only in that container, and ArchUnit lets only the worker
 * package use this class.
 */
@Component
@ConditionalOnProperty(name = "app.whatsapp.worker.enabled", havingValue = "true")
public class ChannelUnsealer {
  private final Map<Integer, PrivateKey> keys;

  public ChannelUnsealer(@Value("${app.whatsapp.keys.private-directory:}") String directory) {
    try {
      this.keys =
          directory == null || directory.isBlank()
              ? Map.of()
              : ChannelKeys.privateKeys(Path.of(directory));
    } catch (IOException invalid) {
      throw new UncheckedIOException(invalid);
    }
  }

  public boolean configured() {
    return !keys.isEmpty();
  }

  /**
   * Whether the newest public key of the sealer has its private pair here: a probe value sealed
   * with it opens. Checked at worker start so a half-generated pair raises an alarm.
   */
  public boolean opens(ChannelSealer sealer) {
    if (!sealer.configured()) return false;
    java.util.UUID probe = java.util.UUID.randomUUID();
    try {
      SealedSecret sealed = sealer.seal(probe, ChannelSecretPurpose.ACCESS_TOKEN, "probe");
      return "probe".equals(open(probe, ChannelSecretPurpose.ACCESS_TOKEN, sealed));
    } catch (ChannelSecretException mismatch) {
      return false;
    }
  }

  /** The secret in clear, for the immediate Graph API call only; never store nor log it. */
  public String open(UUID channelId, ChannelSecretPurpose purpose, SealedSecret sealed) {
    byte[] value = sealed.value();
    int version;
    try {
      version = SealedBox.version(value);
    } catch (IllegalArgumentException malformed) {
      throw new ChannelSecretException(ChannelSecretException.Reason.INVALID);
    }
    if (version != sealed.keyVersion()) {
      throw new ChannelSecretException(ChannelSecretException.Reason.INVALID);
    }
    PrivateKey key = keys.get(version);
    if (key == null) throw new ChannelSecretException(ChannelSecretException.Reason.NOT_CONFIGURED);
    byte[] opened = null;
    try {
      opened = SealedBox.open(key, purpose.context(channelId), value);
      return new String(opened, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | RuntimeException tampered) {
      throw new ChannelSecretException(ChannelSecretException.Reason.INVALID);
    } finally {
      // Best effort: the String itself cannot be wiped.
      if (opened != null) java.util.Arrays.fill(opened, (byte) 0);
    }
  }
}
