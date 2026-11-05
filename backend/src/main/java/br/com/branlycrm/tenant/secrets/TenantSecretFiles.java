package br.com.branlycrm.tenant.secrets;

import br.com.branlycrm.tenant.domain.TenantDatabase;
import br.com.branlycrm.tenant.domain.TenantSecret;
import br.com.branlycrm.tenant.secrets.TenantSecretException.Reason;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * File layout shared by reader and writer. One regular file per clinic, named after the registry
 * credential reference (never a path from a request):
 *
 * <pre>
 * tenant=&lt;uuid&gt;
 * version=&lt;positive integer&gt;
 * password=&lt;value&gt;
 * </pre>
 */
final class TenantSecretFiles {
  static final int MAX_BYTES = 1024;
  private static final Pattern REFERENCE = Pattern.compile("TENANT_SECRET_[A-F0-9]{32}");
  private static final Pattern VERSION = Pattern.compile("[1-9][0-9]{0,17}");

  private TenantSecretFiles() {}

  /** The configured directory is trusted server configuration and must already exist. */
  static Path directory(String configured) {
    if (configured == null || configured.isBlank()) {
      throw new IllegalStateException("Tenant secret directory is not configured");
    }
    try {
      Path directory = Path.of(configured).toRealPath();
      if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalStateException("Tenant secret directory is not a directory");
      }
      return directory;
    } catch (IOException exception) {
      throw new IllegalStateException("Tenant secret directory is unavailable", exception);
    }
  }

  static Path file(Path directory, TenantDatabase database) {
    String reference = database.credentialReference();
    if (!REFERENCE.matcher(reference).matches()) {
      throw new TenantSecretException(Reason.INVALID, "Invalid tenant secret reference");
    }
    Path file = directory.resolve(reference);
    if (!directory.equals(file.getParent())) {
      throw new TenantSecretException(Reason.INVALID, "Invalid tenant secret reference");
    }
    return file;
  }

  static Optional<TenantSecret> read(Path file, UUID expectedTenant) {
    byte[] content;
    try {
      BasicFileAttributes attributes =
          Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
        throw new TenantSecretException(Reason.INVALID, "Tenant secret is not a regular file");
      }
      if (attributes.size() > MAX_BYTES) {
        throw new TenantSecretException(Reason.INVALID, "Tenant secret is too large");
      }
      try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
        content = input.readNBytes(MAX_BYTES + 1);
      }
    } catch (NoSuchFileException absent) {
      return Optional.empty();
    } catch (IOException exception) {
      throw new TenantSecretException(Reason.UNAVAILABLE, "Tenant secret is unreadable", exception);
    }
    if (content.length > MAX_BYTES) {
      throw new TenantSecretException(Reason.INVALID, "Tenant secret is too large");
    }
    return Optional.of(parse(new String(content, StandardCharsets.US_ASCII), expectedTenant));
  }

  static byte[] serialize(TenantSecret secret) {
    return ("tenant="
            + secret.tenantId()
            + "\nversion="
            + secret.version()
            + "\npassword="
            + secret.password()
            + "\n")
        .getBytes(StandardCharsets.US_ASCII);
  }

  private static TenantSecret parse(String content, UUID expectedTenant) {
    String body = content.endsWith("\n") ? content.substring(0, content.length() - 1) : content;
    String[] lines = body.split("\n", -1);
    if (lines.length != 3
        || !lines[0].startsWith("tenant=")
        || !lines[1].startsWith("version=")
        || !lines[2].startsWith("password=")) {
      throw malformed();
    }
    String tenant = lines[0].substring("tenant=".length());
    String version = lines[1].substring("version=".length());
    if (!expectedTenant.toString().equals(tenant)) {
      throw new TenantSecretException(Reason.INVALID, "Tenant secret belongs to another clinic");
    }
    if (!VERSION.matcher(version).matches()) throw malformed();
    try {
      return new TenantSecret(
          expectedTenant, Long.parseLong(version), lines[2].substring("password=".length()));
    } catch (IllegalArgumentException exception) {
      throw malformed();
    }
  }

  private static TenantSecretException malformed() {
    return new TenantSecretException(Reason.INVALID, "Tenant secret is malformed");
  }
}
