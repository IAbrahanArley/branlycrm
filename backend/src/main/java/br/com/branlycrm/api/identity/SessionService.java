package br.com.branlycrm.api.identity;
import java.util.List;
import java.util.UUID;
import br.com.branlycrm.api.tenancy.TenantAccessService;
import br.com.branlycrm.api.tenancy.TenantRegistryRepository;
import org.springframework.stereotype.Service;

@Service
public class SessionService {
    private final TenantAccessService access;
    private final TenantRegistryRepository tenants;
    public SessionService(TenantAccessService access, TenantRegistryRepository tenants) {
        this.access = access;
        this.tenants = tenants;
    }
    public List<TenantRegistryRepository.Membership> memberships(CrmPrincipal principal) {
        return tenants.findMemberships(principal.subjectId());
    }
    public void authorizeSelection(CrmPrincipal principal, UUID tenantId) {
        access.authorize(tenantId, principal.subjectId());
    }
}