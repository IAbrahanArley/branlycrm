package br.com.branlycrm.pipeline.dto.response;

import java.time.Instant;
import java.util.UUID;

/** A lead in lists and the Kanban, with its contact's name and phone. */
public record LeadSummaryView(
    UUID id,
    UUID contactId,
    String contactName,
    String contactPhone,
    UUID stageId,
    String status,
    String temperature,
    UUID responsibleId,
    Instant openedAt,
    long version,
    LeadOriginView origin) {
  @Override
  public String toString() {
    return "LeadSummaryView[id=" + id + "]";
  }
}
