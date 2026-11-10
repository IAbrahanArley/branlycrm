package br.com.branlycrm.pipeline.services;

import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.common.domain.PageResult;
import br.com.branlycrm.common.dto.response.PageView;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.pipeline.domain.LeadNote;
import br.com.branlycrm.pipeline.domain.LeadOrigin;
import br.com.branlycrm.pipeline.domain.LeadSearch;
import br.com.branlycrm.pipeline.domain.LeadStatus;
import br.com.branlycrm.pipeline.domain.LeadSummary;
import br.com.branlycrm.pipeline.domain.Temperature;
import br.com.branlycrm.pipeline.dto.request.LossRequest;
import br.com.branlycrm.pipeline.dto.request.NoteRequest;
import br.com.branlycrm.pipeline.dto.request.OpenLeadRequest;
import br.com.branlycrm.pipeline.dto.request.OriginRequest;
import br.com.branlycrm.pipeline.dto.request.ResponsibleChangeRequest;
import br.com.branlycrm.pipeline.dto.request.StageChangeRequest;
import br.com.branlycrm.pipeline.dto.request.TemperatureChangeRequest;
import br.com.branlycrm.pipeline.dto.response.LeadNoteView;
import br.com.branlycrm.pipeline.dto.response.LeadSummaryView;
import br.com.branlycrm.pipeline.dto.response.LeadView;
import br.com.branlycrm.pipeline.repositories.LeadRepository;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.domain.ClinicPermission;
import br.com.branlycrm.tenant.services.TenantAccessService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Lead HTTP inputs (CRM-032): validates formats, then delegates the rules to {@link LeadService};
 * lists and notes are read here. Every invalid field is reported by name, before any access check.
 */
@Service
public class LeadRequestService {
  private final TenantAccessService access;
  private final LeadService leads;
  private final LeadRepository repository;

  public LeadRequestService(
      TenantAccessService access, LeadService leads, LeadRepository repository) {
    this.access = access;
    this.leads = leads;
    this.repository = repository;
  }

  public PageView<LeadSummaryView> list(
      UUID tenantId,
      UUID actor,
      String status,
      String stageId,
      String responsibleId,
      String temperature,
      String contactId,
      String page,
      String pageSize) {
    var search =
        new LeadSearch(
            optional(LeadStatus.class, status, "status"),
            uuid(stageId, "stageId", false),
            uuid(responsibleId, "responsibleId", false),
            optional(Temperature.class, temperature, "temperature"),
            uuid(contactId, "contactId", false));
    PageQuery paging = PageQuery.of(page, pageSize);
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.LEAD_READ);
    PageResult<LeadSummary> result = repository.search(clinic, search, paging);
    return new PageView<>(
        result.items().stream().map(LeadRequestService::summary).toList(),
        paging.page(),
        paging.pageSize(),
        result.total());
  }

  public LeadView open(UUID tenantId, UUID actor, OpenLeadRequest request) {
    if (request == null) throw new InvalidInputException("body");
    UUID contact = uuid(request.contactId(), "contactId", true);
    Temperature temperature = optional(Temperature.class, request.temperature(), "temperature");
    UUID responsible = uuid(request.responsibleId(), "responsibleId", false);
    if (request.origin() == null) {
      return leads.open(tenantId, actor, contact, temperature, responsible);
    }
    LeadOrigin origin = manual(request.origin());
    return leads.open(tenantId, actor, contact, temperature, responsible, origin);
  }

  public LeadView correctOrigin(UUID tenantId, UUID actor, UUID leadId, OriginRequest request) {
    if (request == null) throw new InvalidInputException("body");
    LeadOrigin origin = manual(request);
    return leads.correctOrigin(tenantId, actor, leadId, version(request.version()), origin);
  }

  private static LeadOrigin manual(OriginRequest request) {
    LeadOrigin.Type type = optional(LeadOrigin.Type.class, request.type(), "type");
    if (type == null) throw new InvalidInputException("type");
    return LeadOrigin.manual(type, request.code(), request.title());
  }

  public LeadView moveStage(UUID tenantId, UUID actor, UUID leadId, StageChangeRequest request) {
    if (request == null) throw new InvalidInputException("body");
    UUID stage = uuid(request.stageId(), "stageId", true);
    return leads.moveStage(tenantId, actor, leadId, version(request.version()), stage);
  }

  public LeadView changeTemperature(
      UUID tenantId, UUID actor, UUID leadId, TemperatureChangeRequest request) {
    if (request == null) throw new InvalidInputException("body");
    Temperature temperature = optional(Temperature.class, request.temperature(), "temperature");
    if (temperature == null) throw new InvalidInputException("temperature");
    return leads.changeTemperature(
        tenantId, actor, leadId, version(request.version()), temperature);
  }

  public LeadView assign(UUID tenantId, UUID actor, UUID leadId, ResponsibleChangeRequest request) {
    if (request == null) throw new InvalidInputException("body");
    UUID responsible = uuid(request.responsibleId(), "responsibleId", false);
    return leads.assign(tenantId, actor, leadId, version(request.version()), responsible);
  }

  /** A loss without reason is refused as invalid input (CRM-06). */
  public LeadView lose(UUID tenantId, UUID actor, UUID leadId, LossRequest request) {
    if (request == null) throw new InvalidInputException("body");
    UUID reason = uuid(request.lossReasonId(), "lossReasonId", true);
    return leads.lose(tenantId, actor, leadId, version(request.version()), reason);
  }

  public LeadNoteView addNote(UUID tenantId, UUID actor, UUID leadId, NoteRequest request) {
    String body = LeadNote.body(request == null ? null : request.body());
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.LEAD_WRITE);
    return note(repository.addNote(clinic, leadId, body));
  }

  public List<LeadNoteView> notes(UUID tenantId, UUID actor, UUID leadId) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.LEAD_READ);
    return repository.notes(clinic, leadId).stream().map(LeadRequestService::note).toList();
  }

  private static LeadSummaryView summary(LeadSummary summary) {
    var lead = summary.lead();
    return new LeadSummaryView(
        lead.id(),
        lead.contactId(),
        summary.contactName(),
        summary.contactPhone(),
        lead.stageId(),
        lead.status().name(),
        lead.temperature() == null ? null : lead.temperature().name(),
        lead.responsibleId(),
        lead.openedAt(),
        lead.version(),
        LeadService.origin(lead.origin()));
  }

  private static LeadNoteView note(LeadNote note) {
    return new LeadNoteView(note.id(), note.body(), note.authorId(), note.createdAt());
  }

  private static long version(Long version) {
    if (version == null || version < 0) throw new InvalidInputException("version");
    return version;
  }

  private static UUID uuid(String value, String field, boolean required) {
    if (value == null || value.isEmpty()) {
      if (required) throw new InvalidInputException(field);
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException invalid) {
      throw new InvalidInputException(field);
    }
  }

  private static <E extends Enum<E>> E optional(Class<E> type, String value, String field) {
    if (value == null || value.isEmpty()) return null;
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException invalid) {
      throw new InvalidInputException(field);
    }
  }
}
