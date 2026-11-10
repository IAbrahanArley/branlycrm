package br.com.branlycrm.pipeline.domain;

import java.time.Instant;
import java.util.UUID;

/** A later arrival of a contact with its detected origin; it never changes the lead (RN-02). */
public record OriginTouch(
    UUID id, UUID contactId, UUID leadId, LeadOrigin origin, Instant receivedAt) {
  public OriginTouch {
    if (id == null || contactId == null || origin == null || receivedAt == null) {
      throw new IllegalArgumentException("Incomplete origin touch");
    }
  }
}
