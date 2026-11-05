package br.com.branlycrm.tenant.config;

import br.com.branlycrm.tenant.secrets.FileTenantSecretReader;
import br.com.branlycrm.tenant.secrets.TenantSecretReader;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TenantSecretsConfiguration {
  /**
   * Without a configured directory there are no clinic credentials in this environment, so every
   * clinic connection fails closed. A configured directory must exist at startup.
   */
  @Bean
  TenantSecretReader tenantSecretReader(
      @Value("${app.tenancy.secrets.directory:}") String directory) {
    if (directory.isBlank()) return database -> Optional.empty();
    return new FileTenantSecretReader(directory);
  }
}
