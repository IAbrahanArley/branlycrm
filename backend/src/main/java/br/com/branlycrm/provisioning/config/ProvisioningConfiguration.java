package br.com.branlycrm.provisioning.config;

import br.com.branlycrm.provisioning.repositories.ProvisioningRegistryRepository;
import br.com.branlycrm.provisioning.repositories.ProvisioningTaskRepository;
import br.com.branlycrm.provisioning.repositories.TenantInfrastructureRepository;
import br.com.branlycrm.provisioning.repositories.TenantRuntimeProbeRepository;
import br.com.branlycrm.provisioning.services.TenantProvisioningService;
import br.com.branlycrm.tenant.secrets.FileTenantSecretWriter;
import br.com.branlycrm.tenant.secrets.TenantSecretReader;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exists only in the provisioner process ({@code APP_MODE=provision}). The API never sets {@code
 * app.provisioning.enabled}, so it never receives the provisioner credential, the secret writer or
 * these beans (ADR-016).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.provisioning.enabled", havingValue = "true")
public class ProvisioningConfiguration {

  @Bean
  TenantProvisioningService tenantProvisioningService(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      TenantSecretReader secrets,
      @Value("${app.tenancy.host:localhost}") String host,
      @Value("${app.tenancy.port:5432}") int port,
      @Value("${app.tenancy.secrets.directory}") String secretsDirectory,
      @Value("${app.provisioning.admin.database:postgres}") String maintenanceDatabase,
      @Value("${app.provisioning.admin.username}") String username,
      @Value("${app.provisioning.admin.password}") String password,
      @Value("${app.provisioning.control-database}") String controlDatabase,
      @Value("${app.provisioning.lease:PT5M}") Duration lease,
      @Value("${app.provisioning.backoff:PT30S}") Duration backoff) {
    return new TenantProvisioningService(
        new ProvisioningTaskRepository(jdbc),
        new ProvisioningRegistryRepository(jdbc),
        new TenantInfrastructureRepository(host, port, maintenanceDatabase, username, password),
        new TenantRuntimeProbeRepository(host, port),
        secrets,
        new FileTenantSecretWriter(secretsDirectory),
        new TransactionTemplate(transactionManager),
        // A clinic runtime role must not reach the control or maintenance databases.
        List.of(controlDatabase, maintenanceDatabase),
        lease,
        backoff);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  // Component scan registers nested configurations on their own, so the outer condition is
  // repeated here: polling runs only in the provisioner process.
  @ConditionalOnProperty(
      name = {"app.provisioning.enabled", "app.provisioning.scheduler.enabled"},
      havingValue = "true")
  static class ProvisioningSchedule {
    private static final Logger log = LoggerFactory.getLogger(ProvisioningSchedule.class);
    private final TenantProvisioningService provisioning;

    ProvisioningSchedule(TenantProvisioningService provisioning) {
      this.provisioning = provisioning;
    }

    @Scheduled(
        fixedDelayString = "${app.provisioning.poll-interval:PT5S}",
        initialDelayString = "${app.provisioning.poll-interval:PT5S}")
    void poll() {
      try {
        provisioning.runPending();
      } catch (DataAccessException unavailable) {
        log.warn("Provisioning queue unavailable; retrying on the next poll");
      }
    }
  }
}
