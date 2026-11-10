package br.com.branlycrm.pipeline.domain;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import java.time.Instant;
import java.util.UUID;

/** A sale of the clinic, as stored. The lead carries the origin, so the sale inherits it. */
public record Sale(
    UUID id,
    UUID leadId,
    UUID contactId,
    SaleFields fields,
    SaleStatus status,
    String cancelReason,
    Instant cancelledAt,
    Instant createdAt,
    UUID createdBy,
    long version) {
  public Sale {
    if (id == null
        || leadId == null
        || contactId == null
        || fields == null
        || fields.soldOn() == null
        || status == null
        || createdAt == null
        || createdBy == null
        || version < 0
        || (status == SaleStatus.CANCELLED) != (cancelledAt != null && cancelReason != null)) {
      throw new IllegalArgumentException("Incomplete sale");
    }
  }

  public static final int REASON_MAX = 500;

  /** Validates the reason of a correction or cancellation: 1..500 characters, one line. */
  public static String reason(String value) {
    String text = value == null ? null : value.strip();
    if (text == null
        || text.isEmpty()
        || text.length() > REASON_MAX
        || text.codePoints().anyMatch(c -> Character.isISOControl(c) || isInvisible(c))) {
      throw new InvalidInputException("reason");
    }
    return text;
  }

  private static boolean isInvisible(int c) {
    int type = Character.getType(c);
    return type == Character.FORMAT
        || type == Character.LINE_SEPARATOR
        || type == Character.PARAGRAPH_SEPARATOR;
  }

  /** The cancel reason is free text: kept out of logs. */
  @Override
  public String toString() {
    return "Sale[id=" + id + ", status=" + status + "]";
  }
}
