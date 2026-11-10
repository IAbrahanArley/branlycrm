package br.com.branlycrm.contact.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A contact as read from the clinic database. {@code patientSince} is kept by the sale transactions
 * (CRM-035): a patient is a contact with at least one active sale; it is never typed.
 */
public record Contact(
    UUID id,
    ContactFields fields,
    ContactCreatedVia createdVia,
    Instant createdAt,
    Instant archivedAt,
    long version,
    Instant patientSince) {
  public Contact {
    if (id == null || fields == null || createdVia == null || createdAt == null) {
      throw new IllegalArgumentException("Incomplete contact");
    }
  }

  public boolean patient() {
    return patientSince != null;
  }

  public boolean archived() {
    return archivedAt != null;
  }

  /** Keeps personal data out of logs and exception messages. */
  @Override
  public String toString() {
    return "Contact[id=" + id + "]";
  }
}
