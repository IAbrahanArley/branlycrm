package br.com.branlycrm.provisioning.config;

import br.com.branlycrm.provisioning.repositories.TenantInfrastructureRepository;
import br.com.branlycrm.provisioning.repositories.TenantRolloutRepository;
import br.com.branlycrm.provisioning.repositories.TenantRuntimeProbeRepository;
import br.com.branlycrm.provisioning.services.TenantMigrationRollout;
import br.com.branlycrm.tenant.secrets.TenantSecretReader;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Exists only in the schema rollout process ({@code APP_MODE=tenant-migrate}, ADR-031). It holds
 * the provisioner credentials but, unlike {@link ProvisioningConfiguration}, never the secret
 * writer nor the provisioning queue: that mode does not set {@code app.provisioning.enabled}, so
 * the worker beans and the scheduler do not exist there.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.provisioning.rollout.enabled", havingValue = "true")
public class TenantRolloutConfiguration {

  @Bean
  TenantMigrationRollout tenantMigrationRollout(
      JdbcTemplate jdbc,
      TenantSecretReader secrets,
      @Value("${app.tenancy.host:localhost}") String host,
      @Value("${app.tenancy.port:5432}") int port,
      @Value("${app.provisioning.admin.database:postgres}") String maintenanceDatabase,
      @Value("${app.provisioning.admin.username}") String username,
      @Value("${app.provisioning.admin.password}") String password,
      @Value("${app.provisioning.control-database}") String controlDatabase) {
    return new TenantMigrationRollout(
        new TenantRolloutRepository(jdbc),
        new TenantInfrastructureRepository(host, port, maintenanceDatabase, username, password),
        new TenantRuntimeProbeRepository(host, port),
        secrets,
        // Same verification as provisioning: the runtime role reaches no other database.
        List.of(controlDatabase, maintenanceDatabase),
        System.out);
  }
}
