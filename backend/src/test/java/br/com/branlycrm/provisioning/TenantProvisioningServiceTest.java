package br.com.branlycrm.provisioning;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.branlycrm.provisioning.domain.ProvisioningStep;
import br.com.branlycrm.provisioning.domain.ProvisioningTask;
import br.com.branlycrm.provisioning.repositories.ProvisioningRegistryRepository;
import br.com.branlycrm.provisioning.repositories.ProvisioningTaskRepository;
import br.com.branlycrm.provisioning.repositories.TenantInfrastructureRepository;
import br.com.branlycrm.provisioning.repositories.TenantRuntimeProbeRepository;
import br.com.branlycrm.provisioning.services.TenantProvisioningService;
import br.com.branlycrm.tenant.domain.TenantDatabase;
import br.com.branlycrm.tenant.domain.TenantSecret;
import br.com.branlycrm.tenant.secrets.TenantSecretReader;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class TenantProvisioningServiceTest {

  /**
   * ADR-031: a task resumed at VERIFICATION (it passed MIGRATION under an older image, or came back
   * through retryFailed) migrates again before the check, so it never activates an old schema.
   */
  @Test
  void verificationMigratesAgainBeforeTheRuntimeCheck() {
    ProvisioningTaskRepository tasks = mock(ProvisioningTaskRepository.class);
    TenantInfrastructureRepository infrastructure = mock(TenantInfrastructureRepository.class);
    TenantRuntimeProbeRepository probe = mock(TenantRuntimeProbeRepository.class);
    TenantSecretReader secrets = mock(TenantSecretReader.class);
    TenantDatabase database = database();
    ProvisioningTask task =
        new ProvisioningTask(database, UUID.randomUUID(), ProvisioningStep.VERIFICATION, 1, 5);
    when(tasks.claimNext(any())).thenReturn(Optional.of(task)).thenReturn(Optional.empty());
    when(tasks.advance(eq(task), any(), any())).thenReturn(true);
    when(secrets.read(database))
        .thenReturn(
            Optional.of(
                new TenantSecret(database.tenantId(), 1, "synthetic-runtime-password-2026")));
    var service =
        new TenantProvisioningService(
            tasks,
            mock(ProvisioningRegistryRepository.class),
            infrastructure,
            probe,
            secrets,
            null,
            mock(TransactionTemplate.class),
            List.of("branlycrm", "postgres"),
            Duration.ofMinutes(5),
            Duration.ZERO);

    service.runPending();

    var order = inOrder(infrastructure, probe, tasks);
    order.verify(infrastructure).migrate(database);
    order
        .verify(probe)
        .verify(database, "synthetic-runtime-password-2026", List.of("branlycrm", "postgres"));
    order.verify(tasks).advance(task, ProvisioningStep.ACTIVATION, Duration.ofMinutes(5));
  }

  private static TenantDatabase database() {
    UUID tenant = UUID.randomUUID();
    String suffix = tenant.toString().replace("-", "");
    return new TenantDatabase(
        tenant,
        "clinic_" + suffix,
        "tenant_" + suffix,
        "TENANT_SECRET_" + suffix.toUpperCase(Locale.ROOT));
  }
}
