package br.com.branlycrm.contact.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A contact as read from the clinic database. Whether it is a lead or a patient is derived from its
 * sales, never stored here.
 */
public record Contact(
    UUID id,
    ContactFields fields,
    ContactCreatedVia createdVia,
    Instant createdAt,
    Instant archivedAt,
    long version) {
  public Contact {
    if (id == null || fields == null || createdVia == null || createdAt == null) {
      throw new IllegalArgumentException("Incomplete contact");
    }
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
