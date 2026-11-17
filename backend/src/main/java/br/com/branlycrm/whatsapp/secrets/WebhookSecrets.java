package br.com.branlycrm.whatsapp.secrets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * App secret and verification token of the webhook (ADR-036), read from mounted files, never from
 * environment variables.
 *
 * <ul>
 *   <li>A missing file leaves the webhook answering 503 instead of failing the boot.
 *   <li>Each file is read again whenever its modification time changes, so mounting or rotating a
 *       secret needs no restart.
 *   <li>The values never reach logs, responses or {@link #toString()}.
 * </ul>
 */
@Component
public class WebhookSecrets {
  private static final Logger LOG = LoggerFactory.getLogger(WebhookSecrets.class);
  private static final int MAX_BYTES = 1024;

  private final SecretFile appSecret;
  private final SecretFile verifyToken;

  public WebhookSecrets(
      @Value("${app.whatsapp.webhook.app-secret-file:}") String appSecretFile,
      @Value("${app.whatsapp.webhook.verify-token-file:}") String verifyTokenFile) {
    this.appSecret = new SecretFile(appSecretFile, "app-secret");
    this.verifyToken = new SecretFile(verifyTokenFile, "verify-token");
  }

  /** The app secret bytes (a fresh copy, which the caller may clear), or empty when not mounted. */
  public Optional<byte[]> appSecret() {
    return appSecret.value().map(text -> text.getBytes(StandardCharsets.UTF_8));
  }

  /** The verification token, or empty when not mounted. */
  public Optional<String> verifyToken() {
    return verifyToken.value();
  }

  @Override
  public String toString() {
    return "WebhookSecrets[redacted]";
  }

  /** One mounted file, cached until its modification time changes. */
  private static final class SecretFile {
    private final Path file;
    private final String name;
    private volatile Cached cached;

    SecretFile(String path, String name) {
      this.file = path == null || path.isBlank() ? null : Path.of(path);
      this.name = name;
    }

    Optional<String> value() {
      if (file == null) return Optional.empty();
      FileTime modified;
      try {
        if (!Files.isRegularFile(file)) return Optional.empty();
        modified = Files.getLastModifiedTime(file);
      } catch (IOException | RuntimeException unreadable) {
        return Optional.empty();
      }
      Cached current = cached;
      if (current == null || !current.modified().equals(modified)) {
        current = new Cached(modified, read());
        cached = current;
      }
      return Optional.ofNullable(current.value());
    }

    private String read() {
      try {
        if (Files.size(file) > MAX_BYTES) {
          LOG.error("WhatsApp webhook secret file too large name={}", name);
          return null;
        }
        String value = Files.readString(file, StandardCharsets.UTF_8).strip();
        if (value.length() < 16) {
          LOG.error("WhatsApp webhook secret file too short name={}", name);
          return null;
        }
        return value;
      } catch (IOException | RuntimeException unreadable) {
        LOG.warn("WhatsApp webhook secret file unreadable name={}", name);
        return null;
      }
    }

    @Override
    public String toString() {
      return "SecretFile[" + name + ", redacted]";
    }
  }

  private record Cached(FileTime modified, String value) {
    @Override
    public String toString() {
      return "Cached[redacted]";
    }
  }
}
