package br.com.branlycrm.tenant.domain;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Runtime credential of one clinic database. The version lets rotation detect concurrent writers
 * and, later, retire pooled connections opened with an older password.
 */
public record TenantSecret(UUID tenantId, long version, String password) {
  private static final Pattern PASSWORD = Pattern.compile("[\\x21-\\x7E]{16,256}");

  public TenantSecret {
    if (tenantId == null) throw new IllegalArgumentException("Tenant is required");
    if (version < 1) throw new IllegalArgumentException("Invalid secret version");
    if (password == null || !PASSWORD.matcher(password).matches()) {
      throw new IllegalArgumentException("Invalid secret value");
    }
  }

  @Override
  public String toString() {
    return "TenantSecret[tenantId=" + tenantId + ", version=" + version + "]";
  }
}
