package br.com.branlycrm.tenant.domain;

import java.util.Locale;
import java.util.UUID;

/**
 * Binding between a clinic and its own database (ADR-014). Every name is derived from the tenant
 * UUID, so a registry row pointing at another clinic's database is rejected before connecting.
 */
public record TenantDatabase(
    UUID tenantId, String databaseName, String databaseUser, String credentialReference) {

  public TenantDatabase {
    if (tenantId == null) throw new IllegalArgumentException("Tenant is required");
    String suffix = suffix(tenantId);
    if (!("clinic_" + suffix).equals(databaseName)
        || !("tenant_" + suffix).equals(databaseUser)
        || !("TENANT_SECRET_" + suffix.toUpperCase(Locale.ROOT)).equals(credentialReference)) {
      throw new IllegalArgumentException("Invalid tenant database binding");
    }
  }

  private static String suffix(UUID tenantId) {
    return tenantId.toString().replace("-", "");
  }
}
