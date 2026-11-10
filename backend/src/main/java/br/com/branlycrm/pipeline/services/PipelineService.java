package br.com.branlycrm.pipeline.services;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.pipeline.domain.Pipeline;
import br.com.branlycrm.pipeline.dto.response.LossReasonView;
import br.com.branlycrm.pipeline.dto.response.PipelineView;
import br.com.branlycrm.pipeline.dto.response.StageView;
import br.com.branlycrm.pipeline.repositories.PipelineRepository;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.domain.ClinicPermission;
import br.com.branlycrm.tenant.services.TenantAccessService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Funnel and loss reasons of the selected clinic, readable by every role that sees leads
 * (LEAD_READ). Input is validated before the access check; no central transaction is held while the
 * clinic database is read.
 */
@Service
public class PipelineService {
  private final TenantAccessService access;
  private final PipelineRepository pipelines;

  public PipelineService(TenantAccessService access, PipelineRepository pipelines) {
    this.access = access;
    this.pipelines = pipelines;
  }

  public PipelineView pipeline(UUID tenantId, UUID actor) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.LEAD_READ);
    Pipeline pipeline = pipelines.get(clinic);
    return new PipelineView(
        pipeline.id(),
        pipeline.name(),
        pipeline.version(),
        pipeline.stages().stream()
            .map(
                stage ->
                    new StageView(
                        stage.id(),
                        stage.name(),
                        stage.kind().name(),
                        stage.key() == null ? null : stage.key().name(),
                        stage.position()))
            .toList());
  }

  public List<LossReasonView> lossReasons(UUID tenantId, UUID actor, String includeArchived) {
    boolean archived = flag(includeArchived);
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.LEAD_READ);
    return pipelines.lossReasons(clinic, archived).stream()
        .map(reason -> new LossReasonView(reason.id(), reason.name(), reason.archived()))
        .toList();
  }

  private static boolean flag(String value) {
    if (value == null || value.isEmpty()) return false;
    if ("true".equals(value)) return true;
    if ("false".equals(value)) return false;
    throw new InvalidInputException("includeArchived");
  }
}
