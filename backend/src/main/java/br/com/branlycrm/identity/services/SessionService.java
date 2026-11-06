package br.com.branlycrm.identity.services;

import br.com.branlycrm.identity.dto.response.ClinicSelectionView;
import br.com.branlycrm.identity.dto.response.ClinicView;
import br.com.branlycrm.identity.dto.response.CurrentClinicView;
import br.com.branlycrm.identity.dto.response.IdentityView;
import br.com.branlycrm.identity.security.CrmPrincipal;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.services.TenantAccessService;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Session use cases. The subject always comes from the authenticated principal. */
@Service
public class SessionService {
  private final TenantAccessService tenants;

  public SessionService(TenantAccessService tenants) {
    this.tenants = tenants;
  }

  public IdentityView identity(CrmPrincipal principal) {
    return new IdentityView(principal.subjectId(), principal.getUsername());
  }

  public List<ClinicView> clinics(CrmPrincipal principal) {
    return tenants.memberships(principal.subjectId()).stream()
        .map(
            membership ->
                new ClinicView(
                    membership.tenantId(), membership.clinicName(), membership.role().name()))
        .toList();
  }

  public CurrentClinicView current(CrmPrincipal principal, UUID tenantId) {
    ClinicAccess access = tenants.authorize(tenantId, principal.subjectId());
    Map<String, String> permissions = new TreeMap<>();
    access
        .role()
        .permissions()
        .forEach((permission, scope) -> permissions.put(permission.name(), scope.name()));
    return new CurrentClinicView(access.tenantId(), access.role().name(), permissions);
  }

  /** Grants no global authority: the selection is revalidated on every request. */
  public ClinicSelectionView select(CrmPrincipal principal, UUID tenantId) {
    tenants.authorize(tenantId, principal.subjectId());
    return new ClinicSelectionView(tenantId);
  }
}
