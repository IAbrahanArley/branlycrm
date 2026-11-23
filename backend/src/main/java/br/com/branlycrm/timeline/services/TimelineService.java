package br.com.branlycrm.timeline.services;

import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.common.dto.response.PageView;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.domain.ClinicPermission;
import br.com.branlycrm.tenant.services.TenantAccessService;
import br.com.branlycrm.timeline.domain.TimelineEntry;
import br.com.branlycrm.timeline.dto.response.TimelineEntryView;
import br.com.branlycrm.timeline.repositories.TimelineRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Contact timeline (CRM-023), readable by every role with LEAD_READ, the same permission that reads
 * a lead's events, notes and sales.
 */
@Service
public class TimelineService {
  private final TenantAccessService access;
  private final TimelineRepository timeline;

  public TimelineService(TenantAccessService access, TimelineRepository timeline) {
    this.access = access;
    this.timeline = timeline;
  }

  public PageView<TimelineEntryView> contact(
      UUID tenantId, UUID actor, UUID contactId, PageQuery query) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.LEAD_READ);
    // Messages may carry health data: they follow the inbox permission, not only the lead one.
    TimelineRepository.Page page =
        timeline.page(clinic, contactId, query, clinic.allows(ClinicPermission.INBOX_READ));
    return new PageView<>(
        page.entries().stream().map(TimelineService::view).toList(),
        query.page(),
        query.pageSize(),
        page.total());
  }

  private static TimelineEntryView view(TimelineEntry entry) {
    return new TimelineEntryView(
        entry.kind(),
        entry.id(),
        entry.occurredAt(),
        entry.actorId(),
        entry.leadId(),
        entry.saleId(),
        entry.taskId(),
        entry.type(),
        entry.fromValue(),
        entry.toValue(),
        entry.lossReasonId(),
        entry.amountCents(),
        entry.text(),
        entry.conversationId(),
        entry.messageType(),
        entry.sentByApp());
  }
}
