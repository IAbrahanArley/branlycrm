package br.com.branlycrm.whatsapp.secrets;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Seals channel secrets with the newest public key (ADR-036). Present in the API and the worker; it
 * can never open what it seals. Without a mounted public key, sealing fails with NOT_CONFIGURED and
 * the rest of the application keeps working.
 */
@Component
public class ChannelSealer {
  private final Map<Integer, PublicKey> keys;

  public ChannelSealer(@Value("${app.whatsapp.keys.public-directory:}") String directory) {
    try {
      this.keys =
          directory == null || directory.isBlank()
              ? Map.of()
              : new TreeMap<>(ChannelKeys.publicKeys(Path.of(directory)));
    } catch (IOException invalid) {
      throw new UncheckedIOException(invalid);
    }
  }

  public boolean configured() {
    return !keys.isEmpty();
  }

  public SealedSecret seal(UUID channelId, ChannelSecretPurpose purpose, String secret) {
    if (secret == null || secret.isEmpty()) throw new IllegalArgumentException("Empty secret");
    if (keys.isEmpty())
      throw new ChannelSecretException(ChannelSecretException.Reason.NOT_CONFIGURED);
    int version = ((TreeMap<Integer, PublicKey>) keys).lastKey();
    try {
      byte[] sealed =
          SealedBox.seal(
              keys.get(version),
              version,
              purpose.context(channelId),
              secret.getBytes(StandardCharsets.UTF_8));
      return new SealedSecret(sealed, version);
    } catch (GeneralSecurityException failure) {
      throw new ChannelSecretException(ChannelSecretException.Reason.INVALID);
    }
  }
}
