package br.com.branlycrm.catalog.persistence;

import br.com.branlycrm.tenant.persistence.ClinicEntityRegistration;
import java.util.List;
import org.springframework.stereotype.Component;

/** Clinic entities owned by the catalog module (ADR-030). */
@Component
class CatalogClinicEntities implements ClinicEntityRegistration {
  @Override
  public List<Class<?>> entities() {
    return List.of(ProcedureEntity.class, ProfessionalEntity.class);
  }
}
