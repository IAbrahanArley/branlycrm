package br.com.branlycrm.pipeline.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One immutable timeline entry. {@code fromValue} and {@code toValue} hold a stage id, a
 * temperature or a subject id, depending on the type.
 */
public record LeadEvent(
    UUID id,
    UUID leadId,
    LeadEventType type,
    String fromValue,
    String toValue,
    UUID lossReasonId,
    UUID actorId,
    Instant occurredAt) {
  public LeadEvent {
    if (id == null || leadId == null || type == null || occurredAt == null) {
      throw new IllegalArgumentException("Incomplete lead event");
    }
  }
}
