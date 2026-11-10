package br.com.branlycrm.pipeline.domain;

import java.time.Instant;
import java.util.UUID;

/** A lead (opportunity) of a contact, as read from the clinic database. */
public record Lead(
    UUID id,
    UUID contactId,
    UUID stageId,
    LeadStatus status,
    Temperature temperature,
    UUID responsibleId,
    UUID lossReasonId,
    Instant openedAt,
    Instant closedAt,
    long version) {
  public Lead {
    if (id == null || contactId == null || stageId == null || status == null || openedAt == null) {
      throw new IllegalArgumentException("Incomplete lead");
    }
  }

  public boolean open() {
    return status == LeadStatus.OPEN;
  }
}
