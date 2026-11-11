package br.com.branlycrm.pipeline.services;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.pipeline.domain.ConfigName;
import br.com.branlycrm.pipeline.domain.LossReason;
import br.com.branlycrm.pipeline.domain.Pipeline;
import br.com.branlycrm.pipeline.dto.request.ConfigArchiveRequest;
import br.com.branlycrm.pipeline.dto.request.ConfigNameRequest;
import br.com.branlycrm.pipeline.dto.request.StageOrderRequest;
import br.com.branlycrm.pipeline.dto.response.LossReasonView;
import br.com.branlycrm.pipeline.dto.response.PipelineView;
import br.com.branlycrm.pipeline.dto.response.StageView;
import br.com.branlycrm.pipeline.exceptions.PipelineException;
import br.com.branlycrm.pipeline.repositories.PipelineConfigRepository;
import br.com.branlycrm.pipeline.repositories.PipelineRepository;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.domain.ClinicPermission;
import br.com.branlycrm.tenant.services.TenantAccessService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Funnel and loss reasons of the selected clinic, readable by every role that sees leads
 * (LEAD_READ) and configured by owner and manager (COMMERCIAL_SETTINGS_EDIT, CRM-036). Input is
 * validated before the access check; no central transaction is held while the clinic database is
 * read.
 */
@Service
public class PipelineService {
  private final TenantAccessService access;
  private final PipelineRepository pipelines;
  private final PipelineConfigRepository config;

  public PipelineService(
      TenantAccessService access, PipelineRepository pipelines, PipelineConfigRepository config) {
    this.access = access;
    this.pipelines = pipelines;
    this.config = config;
  }

  public PipelineView pipeline(UUID tenantId, UUID actor) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.LEAD_READ);
    return view(clinic);
  }

  public List<LossReasonView> lossReasons(UUID tenantId, UUID actor, String includeArchived) {
    boolean archived = flag(includeArchived);
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.LEAD_READ);
    return pipelines.lossReasons(clinic, archived).stream().map(PipelineService::view).toList();
  }

  /** Adds an open stage at the end; {@code version} is the funnel's. */
  public PipelineView createStage(UUID tenantId, UUID actor, ConfigNameRequest request) {
    String name = ConfigName.stage(body(request).name());
    long version = version(request.version());
    ClinicAccess clinic = edit(tenantId, actor);
    config.createStage(clinic, version, name);
    return view(clinic);
  }

  public PipelineView renameStage(
      UUID tenantId, UUID actor, UUID stageId, ConfigNameRequest request) {
    String name = ConfigName.stage(body(request).name());
    long version = version(request.version());
    ClinicAccess clinic = edit(tenantId, actor);
    config.renameStage(clinic, version, stageId, name);
    return view(clinic);
  }

  public PipelineView reorder(UUID tenantId, UUID actor, StageOrderRequest request) {
    if (request == null || request.stageIds() == null || request.stageIds().isEmpty()) {
      throw new InvalidInputException("stageIds");
    }
    if (request.stageIds().size() > PipelineConfigRepository.MAX_OPEN_STAGES) {
      throw new InvalidInputException("stageIds");
    }
    List<UUID> order = new ArrayList<>();
    for (String id : request.stageIds()) order.add(uuid(id, "stageIds"));
    long version = version(request.version());
    ClinicAccess clinic = edit(tenantId, actor);
    config.reorder(clinic, version, order);
    return view(clinic);
  }

  public PipelineView setStageArchived(
      UUID tenantId, UUID actor, UUID stageId, ConfigArchiveRequest request) {
    boolean archived = archived(request);
    long version = version(request.version());
    ClinicAccess clinic = edit(tenantId, actor);
    config.setStageArchived(clinic, version, stageId, archived);
    return view(clinic);
  }

  public LossReasonView createLossReason(UUID tenantId, UUID actor, ConfigNameRequest request) {
    String name = ConfigName.lossReason(body(request).name());
    ClinicAccess clinic = edit(tenantId, actor);
    return reason(clinic, config.createLossReason(clinic, name));
  }

  public LossReasonView renameLossReason(
      UUID tenantId, UUID actor, UUID reasonId, ConfigNameRequest request) {
    String name = ConfigName.lossReason(body(request).name());
    long version = version(request.version());
    ClinicAccess clinic = edit(tenantId, actor);
    config.renameLossReason(clinic, reasonId, version, name);
    return reason(clinic, reasonId);
  }

  public LossReasonView setLossReasonArchived(
      UUID tenantId, UUID actor, UUID reasonId, ConfigArchiveRequest request) {
    boolean archived = archived(request);
    ClinicAccess clinic = edit(tenantId, actor);
    config.setLossReasonArchived(clinic, reasonId, archived);
    return reason(clinic, reasonId);
  }

  private ClinicAccess edit(UUID tenantId, UUID actor) {
    return access.require(tenantId, actor, ClinicPermission.COMMERCIAL_SETTINGS_EDIT);
  }

  private PipelineView view(ClinicAccess clinic) {
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
            .toList(),
        config.archivedStages(clinic).stream()
            .map(stage -> new StageView(stage.id(), stage.name(), "OPEN", null, null))
            .toList());
  }

  private LossReasonView reason(ClinicAccess clinic, UUID id) {
    return pipelines.lossReasons(clinic, true).stream()
        .filter(reason -> reason.id().equals(id))
        .findFirst()
        .map(PipelineService::view)
        .orElseThrow(() -> new PipelineException(PipelineException.Reason.LOSS_REASON_NOT_FOUND));
  }

  private static LossReasonView view(LossReason reason) {
    return new LossReasonView(reason.id(), reason.name(), reason.archived(), reason.version());
  }

  private static <T> T body(T request) {
    if (request == null) throw new InvalidInputException("body");
    return request;
  }

  private static boolean archived(ConfigArchiveRequest request) {
    if (request == null || request.archived() == null) throw new InvalidInputException("archived");
    return request.archived();
  }

  private static long version(Long version) {
    if (version == null || version < 0) throw new InvalidInputException("version");
    return version;
  }

  private static UUID uuid(String value, String field) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw new InvalidInputException(field);
    }
  }

  private static boolean flag(String value) {
    if (value == null || value.isEmpty()) return false;
    if ("true".equals(value)) return true;
    if ("false".equals(value)) return false;
    throw new InvalidInputException("includeArchived");
  }
}
