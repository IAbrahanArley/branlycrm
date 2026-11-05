package br.com.branlycrm.tenant.secrets;

import br.com.branlycrm.tenant.domain.TenantDatabase;
import br.com.branlycrm.tenant.domain.TenantSecret;
import java.util.Optional;

/**
 * Read-only capability given to the API. Writing belongs to the provisioner, which receives a
 * separate {@link FileTenantSecretWriter} and never runs inside the HTTP process.
 *
 * <p>Empty means the secret does not exist yet. A secret that exists but is malformed, a symbolic
 * link, or declares another clinic raises {@link TenantSecretException}: both cases fail closed.
 */
public interface TenantSecretReader {
  Optional<TenantSecret> read(TenantDatabase database);
}
