package br.com.branlycrm.tenant.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.branlycrm.tenant.domain.TenantDatabase;
import br.com.branlycrm.tenant.domain.TenantSecret;
import br.com.branlycrm.tenant.secrets.TenantSecretException.Reason;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileTenantSecretStoreTest {
  private static final String PASSWORD = "synthetic-secret-0001";
  private static final String ROTATED = "synthetic-secret-0002";

  @TempDir Path directory;
  FileTenantSecretReader reader;
  FileTenantSecretWriter writer;
  TenantDatabase clinic;

  @BeforeEach
  void stores() {
    reader = new FileTenantSecretReader(directory.toString());
    writer = new FileTenantSecretWriter(directory.toString());
    clinic = database(UUID.randomUUID());
  }

  @Test
  void createdSecretIsReadBackWithItsIdentityAndVersion() {
    TenantSecret created = writer.create(clinic, PASSWORD);
    assertThat(created.version()).isOne();
    assertThat(reader.read(clinic)).contains(created);
    assertThat(file(clinic)).hasFileName(clinic.credentialReference());
  }

  @Test
  void absentSecretIsEmpty() {
    assertThat(reader.read(clinic)).isEmpty();
  }

  @Test
  void createNeverOverwritesAnExistingSecret() {
    writer.create(clinic, PASSWORD);
    assertThatThrownBy(() -> writer.create(clinic, ROTATED))
        .isInstanceOf(TenantSecretException.class)
        .extracting(error -> ((TenantSecretException) error).reason())
        .isEqualTo(Reason.ALREADY_EXISTS);
    assertThat(reader.read(clinic).orElseThrow().password()).isEqualTo(PASSWORD);
    assertThat(leftovers()).isEmpty();
  }

  @Test
  void concurrentCreatesPublishExactlyOneSecret() throws Exception {
    int writers = 8;
    var barrier = new CyclicBarrier(writers);
    try (var pool = Executors.newFixedThreadPool(writers)) {
      var results =
          Stream.<Callable<Boolean>>generate(
                  () ->
                      () -> {
                        barrier.await();
                        try {
                          writer.create(clinic, "synthetic-" + UUID.randomUUID());
                          return true;
                        } catch (TenantSecretException exception) {
                          assertThat(exception.reason()).isEqualTo(Reason.ALREADY_EXISTS);
                          return false;
                        }
                      })
              .limit(writers)
              .map(pool::submit)
              .toList();
      long published = 0;
      for (Future<Boolean> result : results) if (result.get()) published++;
      assertThat(published).isOne();
    }
    assertThat(reader.read(clinic)).isPresent();
    assertThat(leftovers()).isEmpty();
  }

  @Test
  void rotationRequiresTheExpectedVersion() {
    writer.create(clinic, PASSWORD);
    TenantSecret rotated = writer.rotate(clinic, 1, ROTATED);
    assertThat(rotated.version()).isEqualTo(2);
    assertThat(reader.read(clinic)).contains(rotated);
    assertThatThrownBy(() -> writer.rotate(clinic, 1, PASSWORD))
        .isInstanceOf(TenantSecretException.class)
        .extracting(error -> ((TenantSecretException) error).reason())
        .isEqualTo(Reason.VERSION_CONFLICT);
    assertThat(reader.read(clinic)).contains(rotated);
    assertThatThrownBy(() -> writer.rotate(database(UUID.randomUUID()), 1, ROTATED))
        .isInstanceOf(TenantSecretException.class)
        .extracting(error -> ((TenantSecretException) error).reason())
        .isEqualTo(Reason.MISSING);
    assertThat(leftovers()).isEmpty();
  }

  @Test
  void secretDeclaringAnotherClinicIsRejected() throws IOException {
    TenantDatabase other = database(UUID.randomUUID());
    writer.create(other, PASSWORD);
    Files.copy(file(other), file(clinic));
    assertThatThrownBy(() -> reader.read(clinic))
        .isInstanceOf(TenantSecretException.class)
        .hasMessage("Tenant secret belongs to another clinic");
  }

  @Test
  void malformedOrOversizedSecretsFailClosedWithoutEchoingContent() throws IOException {
    String tenant = clinic.tenantId().toString();
    for (String content :
        new String[] {
          "",
          "password=" + PASSWORD,
          "tenant=" + tenant + "\nversion=1\n",
          "tenant=" + tenant + "\nversion=0\npassword=" + PASSWORD,
          "tenant=" + tenant + "\nversion=01\npassword=" + PASSWORD,
          "tenant=" + tenant + "\nversion=+1\npassword=" + PASSWORD,
          "tenant=" + tenant + "\nversion=1\npassword=short",
          "tenant=" + tenant + "\nversion=1\npassword=" + PASSWORD + " with space",
          "tenant=" + tenant + "\nversion=1\npassword=" + PASSWORD + "\nextra=1",
          "tenant=" + tenant + "\r\nversion=1\r\npassword=" + PASSWORD,
          "version=1\ntenant=" + tenant + "\npassword=" + PASSWORD
        }) {
      Files.writeString(file(clinic), content, StandardCharsets.US_ASCII);
      assertThatThrownBy(() -> reader.read(clinic))
          .as(content)
          .isInstanceOf(TenantSecretException.class)
          .hasMessageNotContaining(PASSWORD)
          .extracting(error -> ((TenantSecretException) error).reason())
          .isEqualTo(Reason.INVALID);
    }
    Files.write(file(clinic), new byte[TenantSecretFiles.MAX_BYTES + 1]);
    assertThatThrownBy(() -> reader.read(clinic))
        .isInstanceOf(TenantSecretException.class)
        .hasMessage("Tenant secret is too large");
  }

  @Test
  void symbolicLinksAndDirectoriesAreNotFollowed() throws IOException {
    Path outside = Files.writeString(directory.resolveSibling(UUID.randomUUID() + ".txt"), "x");
    try {
      Files.createSymbolicLink(file(clinic), outside);
    } catch (IOException | UnsupportedOperationException unsupported) {
      assumeTrue(false, "Symbolic links unavailable on this filesystem");
    } finally {
      outside.toFile().deleteOnExit();
    }
    assertThatThrownBy(() -> reader.read(clinic))
        .isInstanceOf(TenantSecretException.class)
        .hasMessage("Tenant secret is not a regular file");
    Files.delete(file(clinic));
    Files.createDirectory(file(clinic));
    assertThatThrownBy(() -> reader.read(clinic))
        .isInstanceOf(TenantSecretException.class)
        .hasMessage("Tenant secret is not a regular file");
  }

  @Test
  void publishedSecretsAreOwnerOnlyOnPosix() throws IOException {
    assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
    writer.create(clinic, PASSWORD);
    assertThat(Files.getPosixFilePermissions(file(clinic)))
        .isEqualTo(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
  }

  @Test
  void invalidValuesAndMissingDirectoriesAreRejected() {
    assertThatIllegalArgumentException().isThrownBy(() -> writer.create(clinic, "short"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> writer.create(clinic, PASSWORD + "\nversion=9"));
    assertThat(reader.read(clinic)).isEmpty();
    assertThatIllegalStateException()
        .isThrownBy(() -> new FileTenantSecretReader(directory.resolve("absent").toString()));
    assertThatIllegalStateException().isThrownBy(() -> new FileTenantSecretReader(" "));
    assertThat(new TenantSecret(clinic.tenantId(), 1, PASSWORD).toString())
        .doesNotContain(PASSWORD);
  }

  private Path file(TenantDatabase database) {
    return directory.resolve(database.credentialReference());
  }

  private Set<Path> leftovers() {
    try (Stream<Path> files = Files.list(directory)) {
      return files
          .filter(path -> path.getFileName().toString().endsWith(".tmp"))
          .collect(java.util.stream.Collectors.toSet());
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static TenantDatabase database(UUID tenant) {
    String suffix = tenant.toString().replace("-", "");
    return new TenantDatabase(
        tenant,
        "clinic_" + suffix,
        "tenant_" + suffix,
        "TENANT_SECRET_" + suffix.toUpperCase(Locale.ROOT));
  }
}
