package br.com.branlycrm.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.branlycrm.provisioning.config.ProvisioningConfiguration;
import br.com.branlycrm.provisioning.config.TenantRolloutConfiguration;
import br.com.branlycrm.provisioning.domain.RolloutOutcome;
import br.com.branlycrm.provisioning.exceptions.ProvisioningException;
import br.com.branlycrm.provisioning.repositories.TenantInfrastructureRepository;
import br.com.branlycrm.provisioning.repositories.TenantRolloutRepository;
import br.com.branlycrm.provisioning.repositories.TenantRuntimeProbeRepository;
import br.com.branlycrm.provisioning.services.TenantMigrationRollout;
import br.com.branlycrm.provisioning.services.TenantProvisioningService;
import br.com.branlycrm.tenant.domain.TenantDatabase;
import br.com.branlycrm.tenant.domain.TenantSecret;
import br.com.branlycrm.tenant.secrets.TenantSecretException;
import br.com.branlycrm.tenant.secrets.TenantSecretReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Schema rollout command (ADR-031): usage, isolation between clinics and mode separation. */
class TenantMigrationRolloutTest {
  private final TenantRolloutRepository clinics = mock(TenantRolloutRepository.class);
  private final TenantInfrastructureRepository infrastructure =
      mock(TenantInfrastructureRepository.class);
  private final TenantRuntimeProbeRepository probe = mock(TenantRuntimeProbeRepository.class);
  private final TenantSecretReader secrets = mock(TenantSecretReader.class);
  private final ByteArrayOutputStream output = new ByteArrayOutputStream();
  private final TenantMigrationRollout rollout =
      new TenantMigrationRollout(
          clinics,
          infrastructure,
          probe,
          secrets,
          List.of("branlycrm", "postgres"),
          new PrintStream(output, true, StandardCharsets.UTF_8));

  @Test
  void invalidUsageExitsWithTwoAndTouchesNothing() {
    for (String[] args :
        List.of(
            new String[] {"--tenant-id"},
            new String[] {"--tenant-id", "not-a-uuid"},
            new String[] {"--tenant-id", "1-1-1-1-1"},
            new String[] {"--all"},
            new String[] {"--tenant-id", UUID.randomUUID().toString(), "--tenant-id", "x"})) {
      rollout.run(args);
      assertThat(rollout.getExitCode()).as(String.join(" ", args)).isEqualTo(2);
    }
    assertThat(output()).contains("Usage: tenant-migrate [--tenant-id UUID]");
    verifyNoInteractions(clinics, infrastructure, probe, secrets);
  }

  @Test
  void oneFailingClinicDoesNotStopTheNext() {
    TenantDatabase a = database();
    TenantDatabase b = database();
    TenantDatabase c = database();
    when(clinics.eligible()).thenReturn(List.of(a, b, c));
    when(infrastructure.migrate(a)).thenReturn(1);
    doThrow(new ProvisioningException("DATABASE_MISSING", new SQLException("relation detail")))
        .when(infrastructure)
        .requireProvisioned(b);
    when(infrastructure.migrate(c)).thenReturn(0);
    when(secrets.read(any())).thenReturn(Optional.of(secret(a)));

    rollout.run();

    assertThat(rollout.getExitCode()).isEqualTo(1);
    assertThat(output())
        .contains("tenantId=" + a.tenantId() + " result=MIGRATED stage=VERIFICATION applied=1\n")
        .contains(
            "tenantId="
                + b.tenantId()
                + " result=FAILED stage=PRECHECK applied=0 code=DATABASE_MISSING\n")
        .contains("tenantId=" + c.tenantId() + " result=UP_TO_DATE stage=VERIFICATION applied=0\n")
        .contains("summary: clinics=3 migrated=1 upToDate=1 failed=1")
        .doesNotContain("relation detail");
    verify(infrastructure, never()).migrate(b);
  }

  @Test
  void migratesBeforeReadingTheSecretAndReportsTheVerificationStage() {
    TenantDatabase a = database();
    when(clinics.eligible(a.tenantId())).thenReturn(Optional.of(a));
    when(infrastructure.migrate(a)).thenReturn(2);
    when(secrets.read(a))
        .thenThrow(new TenantSecretException(TenantSecretException.Reason.INVALID, "detail"));

    rollout.run("--tenant-id", a.tenantId().toString().toUpperCase(Locale.ROOT));

    assertThat(rollout.getExitCode()).isEqualTo(1);
    assertThat(output())
        .contains(
            "tenantId="
                + a.tenantId()
                + " result=FAILED stage=VERIFICATION applied=2 code=SECRET_INVALID")
        .doesNotContain("detail");
    var order = inOrder(infrastructure, secrets);
    order.verify(infrastructure).requireProvisioned(a);
    order.verify(infrastructure).migrate(a);
    order.verify(secrets).read(a);
    verify(probe, never()).verify(any(), anyString(), anyList());
  }

  @Test
  void ineligibleClinicIsAFailureNotAUsageError() {
    UUID unknown = UUID.randomUUID();
    when(clinics.eligible(unknown)).thenReturn(Optional.empty());

    rollout.run("--tenant-id", unknown.toString());

    assertThat(rollout.getExitCode()).isEqualTo(1);
    assertThat(output())
        .contains(
            "tenantId="
                + unknown
                + " result=FAILED stage=SELECTION applied=0 code=TENANT_NOT_ELIGIBLE");
    verifyNoInteractions(infrastructure);
  }

  @Test
  void unreachableControlDatabaseAbortsWithoutItsMessage() {
    when(clinics.eligible())
        .thenThrow(new DataAccessResourceFailureException("password authentication failed"));

    rollout.run();

    assertThat(rollout.getExitCode()).isEqualTo(1);
    assertThat(output())
        .contains("summary: aborted code=CONTROL_UNAVAILABLE")
        .doesNotContain("password");
  }

  @Test
  void unexpectedFailureGetsTheStageCode() {
    TenantDatabase a = database();
    when(clinics.eligible()).thenReturn(List.of(a));
    when(infrastructure.migrate(a)).thenThrow(new IllegalStateException("boom"));

    rollout.run();

    assertThat(output())
        .contains("result=FAILED stage=MIGRATION applied=0 code=MIGRATION_FAILED")
        .doesNotContain("boom");
  }

  @Test
  void outcomeOnlyCarriesStableCodes() {
    UUID id = UUID.randomUUID();
    assertThat(RolloutOutcome.succeeded(id, 0).result())
        .isEqualTo(RolloutOutcome.Result.UP_TO_DATE);
    assertThatThrownBy(
            () -> RolloutOutcome.failed(id, RolloutOutcome.Stage.MIGRATION, 0, "relation x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** The rollout mode gets the command and none of the worker capabilities (ADR-031). */
  @Test
  void rolloutModeHasNoProvisioningWorkerNorScheduler() {
    contextRunner()
        .withPropertyValues(
            "app.provisioning.rollout.enabled=true", "app.provisioning.scheduler.enabled=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(TenantMigrationRollout.class);
              assertThat(context).doesNotHaveBean(TenantProvisioningService.class);
              assertThat(context.getBeanDefinitionNames())
                  .noneMatch(name -> name.contains("ProvisioningSchedule"));
            });
  }

  @Test
  void provisionerModeHasNoRolloutCommand() {
    contextRunner()
        .withPropertyValues("app.provisioning.enabled=true", "app.tenancy.secrets.directory=.")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(TenantProvisioningService.class);
              assertThat(context).doesNotHaveBean(TenantMigrationRollout.class);
            });
  }

  private static ApplicationContextRunner contextRunner() {
    return new ApplicationContextRunner()
        // As in the application: lease and backoff are Duration values written as text.
        .withInitializer(
            context ->
                context
                    .getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
        .withUserConfiguration(ProvisioningConfiguration.class, TenantRolloutConfiguration.class)
        .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
        .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
        .withBean(TenantSecretReader.class, () -> mock(TenantSecretReader.class))
        .withPropertyValues(
            "app.provisioning.admin.username=crm_tenant_provisioner",
            "app.provisioning.admin.password=synthetic-role-password-2026",
            "app.provisioning.control-database=branlycrm");
  }

  private String output() {
    return output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
  }

  private static TenantSecret secret(TenantDatabase database) {
    return new TenantSecret(database.tenantId(), 1, "synthetic-runtime-password-2026");
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
