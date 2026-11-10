package br.com.branlycrm.pipeline.persistence;

import br.com.branlycrm.tenant.persistence.ClinicEntityRegistration;
import java.util.List;
import org.springframework.stereotype.Component;

/** Clinic entities owned by the pipeline module (ADR-030). */
@Component
class PipelineClinicEntities implements ClinicEntityRegistration {
  @Override
  public List<Class<?>> entities() {
    return List.of(
        PipelineEntity.class,
        PipelineStageEntity.class,
        LossReasonEntity.class,
        LeadEntity.class,
        LeadEventEntity.class,
        LeadNoteEntity.class,
        SaleEntity.class,
        SaleEventEntity.class,
        OriginTouchEntity.class);
  }
}
