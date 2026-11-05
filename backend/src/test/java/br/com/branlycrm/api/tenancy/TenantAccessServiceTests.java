package br.com.branlycrm.api.tenancy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import org.springframework.security.access.AccessDeniedException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantAccessServiceTests {
    @Test
    void rejectsMissingIdentityWithoutQueryingDatabase() {
        var database = mock(TenantRegistryRepository.class);
        var service = new TenantAccessService(database);
        assertThatThrownBy(() -> service.authorize(null, UUID.randomUUID()))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.authorize(UUID.randomUUID(), null))
            .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(database);
    }

    @Test
    void rejectsUnknownTenant() {
        var service = new TenantAccessService(mock(TenantRegistryRepository.class));
        assertThatThrownBy(() -> service.authorize(UUID.randomUUID(), UUID.randomUUID()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void preventsBindingAnotherClinicsDatabase() {
        var id = UUID.randomUUID();
        String suffix = id.toString().replace("-", "");
        assertThatThrownBy(() -> new TenantAccessService.TenantDatabase(id,
            "clinic_" + UUID.randomUUID().toString().replace("-", ""),
            "tenant_" + suffix, "TENANT_SECRET_" + suffix.toUpperCase()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}