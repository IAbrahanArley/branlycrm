package br.com.branlycrm.pipeline.repositories;

import br.com.branlycrm.pipeline.domain.LossReason;
import br.com.branlycrm.pipeline.domain.Pipeline;
import br.com.branlycrm.pipeline.persistence.LossReasonEntity;
import br.com.branlycrm.pipeline.persistence.PipelineEntity;
import br.com.branlycrm.pipeline.persistence.PipelineStageEntity;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.exceptions.ClinicDataException;
import br.com.branlycrm.tenant.persistence.ClinicPersistence;
import br.com.branlycrm.tenant.persistence.ClinicTenant;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * The funnel and loss reasons of the clinic authorized by a {@link ClinicAccess} (CRM-030). Read
 * only: configuration writes come with CRM-036. Permission checks belong to the calling service.
 */
@Repository
public class PipelineRepository {
  private final ClinicPersistence persistence;

  public PipelineRepository(ClinicPersistence persistence) {
    this.persistence = persistence;
  }

  /**
   * The clinic's only funnel with its active stages. A database without exactly one funnel, or with
   * stages that break the funnel rules, was changed outside the application: INVALID_DATA.
   */
  public Pipeline get(ClinicAccess access) {
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          List<PipelineEntity> funnels =
              session
                  .createSelectionQuery("from PipelineEntity p", PipelineEntity.class)
                  .setMaxResults(2)
                  .getResultList();
          if (funnels.size() != 1) throw invalid(null);
          PipelineEntity funnel = funnels.getFirst();
          try {
            return new Pipeline(
                funnel.id(),
                funnel.name(),
                funnel.version(),
                session
                    .createSelectionQuery(
                        "from PipelineStageEntity s where s.pipelineId = :funnel"
                            + " and s.archivedAt is null",
                        PipelineStageEntity.class)
                    .setParameter("funnel", funnel.id())
                    .getResultList()
                    .stream()
                    .map(PipelineStageEntity::toDomain)
                    .toList());
          } catch (IllegalArgumentException broken) {
            throw invalid(broken);
          }
        });
  }

  /** Loss reasons ordered by name; archived ones only when asked. */
  public List<LossReason> lossReasons(ClinicAccess access, boolean includeArchived) {
    String where = includeArchived ? "" : " where r.archivedAt is null";
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          try {
            return session
                .createSelectionQuery(
                    "from LossReasonEntity r" + where + " order by lower(r.name), r.id",
                    LossReasonEntity.class)
                .getResultList()
                .stream()
                .map(LossReasonEntity::toDomain)
                .toList();
          } catch (IllegalArgumentException broken) {
            throw invalid(broken);
          }
        });
  }

  private static ClinicDataException invalid(Throwable cause) {
    return new ClinicDataException(ClinicDataException.Reason.INVALID_DATA, cause);
  }
}
