package br.com.branlycrm.pipeline.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry of a sale's history (RN-09): the values after the change and, for a correction, the
 * values before it. A cancellation carries only its reason.
 */
public record SaleEvent(
    UUID id,
    SaleEventType type,
    SaleFields values,
    SaleFields previous,
    String reason,
    UUID actorId,
    Instant occurredAt) {
  public enum SaleEventType {
    CREATED,
    CORRECTED,
    CANCELLED
  }

  public SaleEvent {
    if (id == null || type == null || actorId == null || occurredAt == null) {
      throw new IllegalArgumentException("Incomplete sale event");
    }
  }

  @Override
  public String toString() {
    return "SaleEvent[id=" + id + ", type=" + type + "]";
  }
}
