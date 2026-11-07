package br.com.branlycrm.platform.config;

import br.com.branlycrm.identity.mail.PublicOrigin;
import br.com.branlycrm.platform.repositories.PlatformRepository;
import br.com.branlycrm.platform.services.PlatformCommand;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exists only in the platform process ({@code APP_MODE=platform}), with the crm_platform_operator
 * credential. The API never sets {@code app.platform.enabled}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.platform.enabled", havingValue = "true")
public class PlatformConfiguration {
  @Bean
  PlatformCommand platformCommand(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      @Value("${app.public-origin:http://localhost:8088}") String origin) {
    return new PlatformCommand(
        new PlatformRepository(jdbc),
        new TransactionTemplate(transactionManager),
        PublicOrigin.of(origin),
        System.out);
  }
}
