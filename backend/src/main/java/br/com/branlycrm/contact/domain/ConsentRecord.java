package br.com.branlycrm.contact.domain;

import java.time.Instant;
import java.util.UUID;

/** One immutable entry of a contact's consent history. */
public record ConsentRecord(
    UUID id,
    UUID contactId,
    ConsentChannel channel,
    ConsentStatus status,
    ConsentSource source,
    Instant recordedAt,
    UUID recordedBy) {
  public ConsentRecord {
    if (id == null
        || contactId == null
        || channel == null
        || status == null
        || source == null
        || recordedAt == null) {
      throw new IllegalArgumentException("Incomplete consent record");
    }
  }
}
