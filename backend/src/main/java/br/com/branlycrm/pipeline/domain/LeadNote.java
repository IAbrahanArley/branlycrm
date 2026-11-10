package br.com.branlycrm.pipeline.domain;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import java.time.Instant;
import java.util.UUID;

/** A note on a lead. The body may receive health data: capped and never searched. */
public record LeadNote(UUID id, UUID leadId, String body, UUID authorId, Instant createdAt) {
  public static final int BODY_MAX = 2000;

  public LeadNote {
    if (id == null || leadId == null || authorId == null || createdAt == null) {
      throw new IllegalArgumentException("Incomplete lead note");
    }
    body(body);
  }

  /** The trimmed body, or INVALID_INPUT on "body". */
  public static String body(String value) {
    String body = value == null ? null : value.strip();
    if (body == null || body.isEmpty() || body.length() > BODY_MAX) {
      throw new InvalidInputException("body");
    }
    if (body.chars()
        .anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
      throw new InvalidInputException("body");
    }
    return body;
  }

  @Override
  public String toString() {
    return "LeadNote[id=" + id + "]";
  }
}
