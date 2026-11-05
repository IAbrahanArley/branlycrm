package br.com.branlycrm.tenant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.domain.ClinicRole;
import br.com.branlycrm.tenant.domain.TenantDatabase;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.repositories.TenantConnectionPools;
import br.com.branlycrm.tenant.repositories.TenantConnectionRepository;
import br.com.branlycrm.tenant.repositories.TenantRegistryRepository;
import br.com.branlycrm.tenant.secrets.TenantSecretException;
import br.com.branlycrm.tenant.secrets.TenantSecretReader;
import br.com.branlycrm.tenant.services.TenantAccessService;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantAccessServiceTest {
  private final TenantRegistryRepository registry = mock(TenantRegistryRepository.class);
  private final TenantSecretReader secrets = mock(TenantSecretReader.class);
  private final TenantConnectionPools pools = mock(TenantConnectionPools.class);

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
    var connections = new TenantConnectionRepository(registry, secrets, pools);
    when(registry.findAuthorized(any(), any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> connections.open(UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(TenantAccessException.class);
    assertThatThrownBy(() -> connections.open(null, UUID.randomUUID()))
        .isInstanceOf(TenantAccessException.class);
    verifyNoInteractions(secrets, pools);
  }

  @Test
  void missingSecretFailsClosedWithoutFallback() {
    UUID tenant = UUID.randomUUID();
    String suffix = tenant.toString().replace("-", "");
    when(registry.findAuthorized(any(), any()))
        .thenReturn(
            Optional.of(
                new ClinicAccess(
                    new TenantDatabase(
                        tenant,
                        "clinic_" + suffix,
                        "tenant_" + suffix,
                        "TENANT_SECRET_" + suffix.toUpperCase(Locale.ROOT)),
                    UUID.randomUUID(),
                    ClinicRole.OWNER)));
    when(secrets.read(any())).thenReturn(Optional.empty());
    var connections = new TenantConnectionRepository(registry, secrets, pools);
    assertThatThrownBy(() -> connections.open(tenant, UUID.randomUUID()))
        .isInstanceOf(TenantSecretException.class)
        .hasMessage("Tenant credentials unavailable");
  }

  @Test
  void rejectsUntrustedTenantDatabaseEndpointsAndLimits() {
    var expiry = Duration.ofMinutes(1);
    assertThatThrownBy(() -> new TenantConnectionPools("db/other?x=1", 5432, 1, 1, expiry, () -> 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TenantConnectionPools("db", 0, 1, 1, expiry, () -> 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TenantConnectionPools("db", 5432, 0, 1, expiry, () -> 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TenantConnectionPools("db", 5432, 1, 0, expiry, () -> 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
