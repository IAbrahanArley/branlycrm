package br.com.branlycrm.contact.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A contact as shown in lists and searches: no notes and no birth date, which only the detail
 * returns (data minimization, ADR-032).
 */
public record ContactSummary(
    UUID id,
    String name,
    PhoneNumber phone,
    String email,
    ContactCreatedVia createdVia,
    Instant archivedAt,
    Instant patientSince) {
  public ContactSummary {
    if (id == null || name == null || phone == null || createdVia == null) {
      throw new IllegalArgumentException("Incomplete contact summary");
    }
  }

  /** Keeps personal data out of logs and exception messages. */
  @Override
  public String toString() {
    return "ContactSummary[id=" + id + "]";
  }
}
