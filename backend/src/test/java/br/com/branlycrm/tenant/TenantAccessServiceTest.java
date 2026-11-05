package br.com.branlycrm.tenant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.branlycrm.tenant.domain.TenantDatabase;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.repositories.TenantConnectionRepository;
import br.com.branlycrm.tenant.repositories.TenantRegistryRepository;
import br.com.branlycrm.tenant.services.TenantAccessService;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class TenantAccessServiceTest {
  private final TenantRegistryRepository registry = mock(TenantRegistryRepository.class);

  @Test
  void missingIdentityIsDeniedWithoutQueryingTheRegistry() {
    var service = new TenantAccessService(registry);
    assertThatThrownBy(() -> service.authorize(null, UUID.randomUUID()))
        .isInstanceOf(TenantAccessException.class);
    assertThatThrownBy(() -> service.authorize(UUID.randomUUID(), null))
        .isInstanceOf(TenantAccessException.class);
    verifyNoInteractions(registry);
  }

  @Test
  void unknownTenantOrMembershipIsDenied() {
    when(registry.findAuthorized(any(), any())).thenReturn(Optional.empty());
    var service = new TenantAccessService(registry);
    assertThatThrownBy(() -> service.authorize(UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(TenantAccessException.class)
        .hasMessage("Acesso à clínica negado.");
  }

  @Test
  void connectionRequiresAuthorizationBeforeTouchingSecretsOrTheNetwork() {
    var environment = new MockEnvironment();
    var connections = new TenantConnectionRepository(registry, environment, "localhost", 5432);
    when(registry.findAuthorized(any(), any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> connections.open(UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(TenantAccessException.class);
    assertThatThrownBy(() -> connections.open(null, UUID.randomUUID()))
        .isInstanceOf(TenantAccessException.class);
  }

  @Test
  void missingSecretFailsClosedWithoutFallback() {
    UUID tenant = UUID.randomUUID();
    String suffix = tenant.toString().replace("-", "");
    when(registry.findAuthorized(any(), any()))
        .thenReturn(
            Optional.of(
                new TenantDatabase(
                    tenant,
                    "clinic_" + suffix,
                    "tenant_" + suffix,
                    "TENANT_SECRET_" + suffix.toUpperCase(Locale.ROOT))));
    var connections =
        new TenantConnectionRepository(registry, new MockEnvironment(), "localhost", 5432);
    assertThatThrownBy(() -> connections.open(tenant, UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Tenant credentials unavailable");
  }

  @Test
  void rejectsUntrustedTenantDatabaseEndpoints() {
    var environment = new MockEnvironment();
    assertThatThrownBy(
            () -> new TenantConnectionRepository(registry, environment, "db/other?x=1", 5432))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TenantConnectionRepository(registry, environment, "db", 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
