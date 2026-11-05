package br.com.branlycrm.tenant.secrets;

import br.com.branlycrm.tenant.domain.TenantDatabase;
import br.com.branlycrm.tenant.domain.TenantSecret;
import br.com.branlycrm.tenant.secrets.TenantSecretException.Reason;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Write capability reserved to the provisioner. It is deliberately not a Spring bean: the API
 * process only receives {@link TenantSecretReader}.
 *
 * <p>Files are written to a temporary file in the same directory (owner-only on POSIX), flushed,
 * and then published atomically, so a reader never observes a partial secret.
 */
public final class FileTenantSecretWriter {
  private final Path directory;

  public FileTenantSecretWriter(String directory) {
    this.directory = TenantSecretFiles.directory(directory);
  }

  /**
   * Publishes version 1. Never overwrites: a retry after a partial provisioning failure gets {@link
   * Reason#ALREADY_EXISTS} and must reuse the stored secret instead of generating another.
   */
  public TenantSecret create(TenantDatabase database, String password) {
    TenantSecret secret = new TenantSecret(database.tenantId(), 1, password);
    Path target = TenantSecretFiles.file(directory, database);
    Path temporary = temporary(database, secret);
    try {
      // A hard link fails atomically when the target exists, unlike a replacing rename.
      Files.createLink(target, temporary);
      return secret;
    } catch (FileAlreadyExistsException exists) {
      throw new TenantSecretException(Reason.ALREADY_EXISTS, "Tenant secret already exists");
    } catch (IOException | UnsupportedOperationException exception) {
      throw new TenantSecretException(
          Reason.UNAVAILABLE, "Tenant secret could not be stored", exception);
    } finally {
      deleteQuietly(temporary);
    }
  }

  /**
   * Replaces the secret only when the stored version is still the expected one. A lock file per
   * clinic serializes concurrent rotations across processes sharing the volume.
   */
  public TenantSecret rotate(TenantDatabase database, long expectedVersion, String password) {
    Path target = TenantSecretFiles.file(directory, database);
    Path lockFile = directory.resolve("." + database.credentialReference() + ".lock");
    try (FileChannel channel =
            FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = channel.lock()) {
      TenantSecret current =
          TenantSecretFiles.read(target, database.tenantId())
              .orElseThrow(
                  () -> new TenantSecretException(Reason.MISSING, "Tenant secret does not exist"));
      if (current.version() != expectedVersion) {
        throw new TenantSecretException(
            Reason.VERSION_CONFLICT, "Tenant secret changed since it was read");
      }
      TenantSecret next = new TenantSecret(database.tenantId(), expectedVersion + 1, password);
      Path temporary = temporary(database, next);
      try {
        Files.move(
            temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return next;
      } finally {
        deleteQuietly(temporary);
      }
    } catch (IOException exception) {
      throw new TenantSecretException(
          Reason.UNAVAILABLE, "Tenant secret could not be rotated", exception);
    }
  }

  private Path temporary(TenantDatabase database, TenantSecret secret) {
    Path temporary = null;
    try {
      temporary =
          Files.createTempFile(directory, "." + database.credentialReference() + ".", ".tmp");
      try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        ByteBuffer content = ByteBuffer.wrap(TenantSecretFiles.serialize(secret));
        while (content.hasRemaining()) channel.write(content);
        channel.force(true);
      }
      return temporary;
    } catch (IOException exception) {
      deleteQuietly(temporary);
      throw new TenantSecretException(
          Reason.UNAVAILABLE, "Tenant secret could not be written", exception);
    }
  }

  private static void deleteQuietly(Path file) {
    if (file == null) return;
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      // A leftover temporary file never matches a credential reference, so readers ignore it.
    }
  }
}
