package br.com.branlycrm.tenant.secrets;

import br.com.branlycrm.tenant.domain.TenantDatabase;
import br.com.branlycrm.tenant.domain.TenantSecret;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Local adapter for a private volume mounted read-only in the API container. It does not replace
 * the encryption and access control of a managed secret store, which hosting still has to choose.
 */
public final class FileTenantSecretReader implements TenantSecretReader {
  private final Path directory;

  public FileTenantSecretReader(String directory) {
    this.directory = TenantSecretFiles.directory(directory);
  }

  @Override
  public Optional<TenantSecret> read(TenantDatabase database) {
    return TenantSecretFiles.read(TenantSecretFiles.file(directory, database), database.tenantId());
  }
}
