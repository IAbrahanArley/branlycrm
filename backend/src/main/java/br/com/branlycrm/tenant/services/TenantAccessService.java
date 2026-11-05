package br.com.branlycrm.tenant.services;

import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.domain.ClinicMembership;
import br.com.branlycrm.tenant.domain.ClinicPermission;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.repositories.TenantRegistryRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantAccessService {
  private final TenantRegistryRepository registry;

  public TenantAccessService(TenantRegistryRepository registry) {
    this.registry = registry;
  }

  /** subjectId must come from verified authentication, never from a request parameter. */
  @Transactional(readOnly = true)
  public ClinicAccess authorize(UUID tenantId, UUID subjectId) {
    if (tenantId == null || subjectId == null) throw new TenantAccessException();
    return registry.findAuthorized(tenantId, subjectId).orElseThrow(TenantAccessException::new);
  }

  /** Authorizes and requires the permission; NONE is refused like a missing clinic. */
  @Transactional(readOnly = true)
  public ClinicAccess require(UUID tenantId, UUID subjectId, ClinicPermission permission) {
    ClinicAccess access = authorize(tenantId, subjectId);
    if (!access.allows(permission)) throw new TenantAccessException();
    return access;
  }

  @Transactional(readOnly = true)
  public List<ClinicMembership> memberships(UUID subjectId) {
    return registry.findMemberships(subjectId);
  }
}
