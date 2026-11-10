package br.com.branlycrm.contact.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Contact detail. {@code consent} maps WHATSAPP and EMAIL to OPT_IN, OPT_OUT or NONE. Notes may
 * hold health data: the screen warns before typing (CRM-022). Send {@code version} back to edit.
 */
public record ContactView(
    UUID id,
    String name,
    String phone,
    String email,
    LocalDate birthDate,
    String notes,
    String createdVia,
    Instant createdAt,
    Instant archivedAt,
    long version,
    List<TagView> tags,
    Map<String, String> consent,
    boolean patient) {
  public ContactView {
    tags = List.copyOf(tags);
    // Keeps the channel order in the JSON.
    consent = Collections.unmodifiableMap(new LinkedHashMap<>(consent));
  }

  /** Keeps personal data (and notes, which may hold health data) out of logs. */
  @Override
  public String toString() {
    return "ContactView[id=" + id + "]";
  }
}
