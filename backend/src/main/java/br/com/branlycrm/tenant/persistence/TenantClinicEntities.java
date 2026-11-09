package br.com.branlycrm.tenant.persistence;

import java.util.List;
import org.springframework.stereotype.Component;

/** Clinic entities owned by the tenant module. */
@Component
class TenantClinicEntities implements ClinicEntityRegistration {
  @Override
  public List<Class<?>> entities() {
    return List.of(ClinicSettingsEntity.class);
  }
}
