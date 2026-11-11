package br.com.branlycrm.task.persistence;

import br.com.branlycrm.tenant.persistence.ClinicEntityRegistration;
import java.util.List;
import org.springframework.stereotype.Component;

/** Clinic entities owned by the task module (ADR-030). */
@Component
class TaskClinicEntities implements ClinicEntityRegistration {
  @Override
  public List<Class<?>> entities() {
    return List.of(TaskEntity.class);
  }
}
