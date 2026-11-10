package br.com.branlycrm.contact.dto.response;

import java.time.Instant;
import java.util.UUID;

/** A contact in lists and checks: never notes nor birth date. Patient: has an active sale. */
public record ContactSummaryView(
    UUID id,
    String name,
    String phone,
    String email,
    String createdVia,
    Instant archivedAt,
    boolean patient) {
  /** Keeps personal data out of logs. */
  @Override
  public String toString() {
    return "ContactSummaryView[id=" + id + "]";
  }
}
