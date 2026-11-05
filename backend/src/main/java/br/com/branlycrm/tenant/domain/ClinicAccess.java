package br.com.branlycrm.tenant.domain;

import java.util.UUID;

/**
 * Authorized access of one subject to one clinic, with the role read from the registry in the same
 * check. Built only from an active membership in an active clinic with an active, verified account.
 */
public record ClinicAccess(TenantDatabase database, UUID subjectId, ClinicRole role) {
  public ClinicAccess {
    if (database == null || subjectId == null || role == null) {
      throw new IllegalArgumentException("Incomplete clinic access");
    }
  }

  public UUID tenantId() {
    return database.tenantId();
  }

  public AccessScope scope(ClinicPermission permission) {
    return role.scope(permission);
  }

  /** True when the role reaches the permission at all (ALL or OWN). */
  public boolean allows(ClinicPermission permission) {
    return scope(permission) != AccessScope.NONE;
  }
}
