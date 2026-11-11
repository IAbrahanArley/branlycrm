package br.com.branlycrm.pipeline.services;

import br.com.branlycrm.pipeline.domain.Lead;
import br.com.branlycrm.pipeline.domain.LeadOrigin;
import br.com.branlycrm.pipeline.domain.Temperature;
import br.com.branlycrm.pipeline.dto.response.LeadEventView;
import br.com.branlycrm.pipeline.dto.response.LeadOriginView;
import br.com.branlycrm.pipeline.dto.response.LeadView;
import br.com.branlycrm.pipeline.dto.response.OriginTouchView;
import br.com.branlycrm.pipeline.exceptions.LeadException;
import br.com.branlycrm.pipeline.repositories.LeadRepository;
import br.com.branlycrm.tenant.domain.AccessScope;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.domain.ClinicPermission;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.services.TenantAccessService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Lead rules of the selected clinic (CRM-031): permissions of docs/permissoes.md and the
 * responsible's membership. The HTTP routes come with CRM-032. No central transaction is held while
 * the clinic database is written.
 *
 * <ul>
 *   <li>Open, move and temperature: LEAD_WRITE. Losing: SALE_REGISTER (sales and losses).
 *   <li>Assigning oneself: LEAD_TAKE; assigning or clearing another person: LEAD_ASSIGN.
 *   <li>The responsible must be an active member whose role works leads (LEAD_WRITE).
 * </ul>
 */
@Service
public class LeadService {
  private final TenantAccessService access;
  private final LeadRepository leads;

  public LeadService(TenantAccessService access, LeadRepository leads) {
    this.access = access;
    this.leads = leads;
  }

  public LeadView open(
      UUID tenantId, UUID actor, UUID contactId, Temperature temperature, UUID responsibleId) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.LEAD_WRITE);
    if (responsibleId != null) requireAssignable(tenantId, clinic, null, responsibleId);
    return view(leads.open(clinic, contactId, temperature, responsibleId));
  }

  /** Opens with an origin chosen by a person (MANUAL); LEAD_WRITE and LEAD_ORIGIN_EDIT. */
  public LeadView open(
      UUID tenantId,
      UUID actor,
      UUID contactId,
      Temperature temperature,
      UUID responsibleId,
      LeadOrigin origin) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.LEAD_WRITE);
    if (!clinic.allows(ClinicPermission.LEAD_ORIGIN_EDIT)) throw new TenantAccessException();
    if (responsibleId != null) requireAssignable(tenantId, clinic, null, responsibleId);
    return view(leads.open(clinic, contactId, temperature, responsibleId, origin));
  }

  /** Manual correction of the origin, open or closed lead (LEAD_ORIGIN_EDIT). */
  public LeadView correctOrigin(
      UUID tenantId, UUID actor, UUID leadId, long version, LeadOrigin manual) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.LEAD_ORIGIN_EDIT);
    return view(leads.correctOrigin(clinic, leadId, version, manual));
  }

  /** The contact's later arrivals with their origin, newest first. */
  public List<OriginTouchView> touches(UUID tenantId, UUID actor, UUID contactId) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.LEAD_READ);
    return leads.touches(clinic, contactId).stream()
        .map(
            touch ->
                new OriginTouchView(
                    touch.id(), touch.leadId(), origin(touch.origin()), touch.receivedAt()))
        .toList();
  }

  /** The click id is never returned (data minimization). */
  static LeadOriginView origin(LeadOrigin origin) {
    return new LeadOriginView(
        origin.type().name(), origin.source().name(), origin.adId(), origin.code(), origin.title());
  }

  public LeadView get(UUID tenantId, UUID actor, UUID leadId) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.LEAD_READ);
    return view(
        leads
            .find(clinic, leadId)
            .orElseThrow(() -> new LeadException(LeadException.Reason.NOT_FOUND)));
  }

  public List<LeadEventView> history(UUID tenantId, UUID actor, UUID leadId) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.LEAD_READ);
    return leads.history(clinic, leadId).stream()
        .map(
            event ->
                new LeadEventView(
                    event.id(),
                    event.type().name(),
                    event.fromValue(),
                    event.toValue(),
                    event.lossReasonId(),
                    event.actorId(),
                    event.occurredAt()))
        .toList();
  }

  public LeadView moveStage(UUID tenantId, UUID actor, UUID leadId, long version, UUID stageId) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.LEAD_WRITE);
    return view(leads.moveStage(clinic, leadId, version, stageId));
  }

  public LeadView changeTemperature(
      UUID tenantId, UUID actor, UUID leadId, long version, Temperature temperature) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.LEAD_WRITE);
    return view(leads.changeTemperature(clinic, leadId, version, temperature));
  }

  /** Sets the responsible, or clears it with null. */
  public LeadView assign(UUID tenantId, UUID actor, UUID leadId, long version, UUID responsibleId) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.LEAD_READ);
    Lead current =
        leads
            .find(clinic, leadId)
            .orElseThrow(() -> new LeadException(LeadException.Reason.NOT_FOUND));
    requireAssignable(tenantId, clinic, current.responsibleId(), responsibleId);
    return view(leads.assign(clinic, leadId, version, responsibleId));
  }

  public LeadView lose(UUID tenantId, UUID actor, UUID leadId, long version, UUID lossReasonId) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.SALE_REGISTER);
    if (lossReasonId == null) throw new LeadException(LeadException.Reason.LOSS_REASON_NOT_FOUND);
    return view(leads.lose(clinic, leadId, version, lossReasonId));
  }

  /**
   * Taking a lead for oneself needs LEAD_TAKE; any other change of responsible (to someone else, or
   * away from someone else) needs LEAD_ASSIGN. The new responsible must work leads.
   */
  private void requireAssignable(
      UUID tenantId, ClinicAccess clinic, UUID currentResponsible, UUID newResponsible) {
    UUID actor = clinic.subjectId();
    boolean onlyOneself =
        actor.equals(newResponsible)
            && (currentResponsible == null || actor.equals(currentResponsible));
    ClinicPermission needed =
        onlyOneself ? ClinicPermission.LEAD_TAKE : ClinicPermission.LEAD_ASSIGN;
    if (clinic.scope(needed) == AccessScope.NONE) throw new TenantAccessException();
    if (newResponsible == null) return;
    ClinicAccess responsible;
    try {
      responsible = access.authorize(tenantId, newResponsible);
    } catch (TenantAccessException notAMember) {
      throw new LeadException(LeadException.Reason.RESPONSIBLE_INVALID);
    }
    if (!responsible.allows(ClinicPermission.LEAD_WRITE)) {
      throw new LeadException(LeadException.Reason.RESPONSIBLE_INVALID);
    }
  }

  private static LeadView view(Lead lead) {
    return new LeadView(
        lead.id(),
        lead.contactId(),
        lead.stageId(),
        lead.status().name(),
        lead.temperature() == null ? null : lead.temperature().name(),
        lead.responsibleId(),
        lead.lossReasonId(),
        lead.openedAt(),
        lead.closedAt(),
        lead.version(),
        origin(lead.origin()));
  }
}
