package br.com.branlycrm.api.tenancy;

import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class TenantAccessService {
    private final TenantRegistryRepository repository;

    public TenantAccessService(TenantRegistryRepository repository) {
        this.repository = repository;
    }

    // subjectId must come from verified authentication, never a request parameter.
    public TenantDatabase authorize(UUID tenantId, UUID subjectId) {
        if (tenantId == null || subjectId == null) {
            throw new AccessDeniedException("Tenant access denied");
        }
        return repository.findAuthorized(tenantId, subjectId)
            .orElseThrow(() -> new AccessDeniedException("Tenant access denied"));
    }
    public record TenantDatabase(UUID tenantId, String databaseName,
                                 String databaseUser, String credentialReference) {
        public TenantDatabase {
            if (tenantId == null) throw new IllegalArgumentException("Tenant is required");
            String suffix = tenantId.toString().replace("-", "");
            if (!("clinic_" + suffix).equals(databaseName)
                || !("tenant_" + suffix).equals(databaseUser)
                || !("TENANT_SECRET_" + suffix.toUpperCase(java.util.Locale.ROOT)).equals(credentialReference)) {
                throw new IllegalArgumentException("Invalid tenant database binding");
            }
        }
    }
}