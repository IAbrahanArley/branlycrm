package br.com.branlycrm.tenant.persistence;

import br.com.branlycrm.tenant.domain.ClinicAccess;
import java.util.UUID;

/**
 * Hibernate tenant identifier of a clinic session: the clinic and the authenticated subject whose
 * membership authorizes it. Built by {@link #of(ClinicAccess)} (ArchUnit forbids other callers of
 * the constructor in production code). Even a forged value grants nothing: every connection
 * re-checks the membership in {@code TenantConnectionRepository.open}.
 */
public record ClinicTenant(UUID tenantId, UUID subjectId) {
  public ClinicTenant {
    if (tenantId == null || subjectId == null) {
      throw new IllegalArgumentException("Incomplete clinic tenant");
    }
  }

  public static ClinicTenant of(ClinicAccess access) {
    return new ClinicTenant(access.tenantId(), access.subjectId());
  }

  /** Keeps the subject out of logs and exception messages. */
  @Override
  public String toString() {
    return "ClinicTenant[tenantId=" + tenantId + "]";
  }
}
