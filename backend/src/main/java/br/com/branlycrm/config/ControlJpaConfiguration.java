package br.com.branlycrm.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;

/**
 * The central EntityManager manages no entity (ADR-014, ADR-030). Declaring the managed types
 * replaces Spring Boot's package scan, which would otherwise pick up clinic entities and point them
 * at the control database. Clinic entities belong to the clinic persistence unit only.
 */
@Configuration(proxyBeanMethods = false)
public class ControlJpaConfiguration {
  @Bean
  PersistenceManagedTypes persistenceManagedTypes() {
    return PersistenceManagedTypes.of(List.of(), List.of());
  }
}
