package br.com.branlycrm.inbox.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A reply queued for sending (CRM-084a). The text never appears in {@link #toString()}.
 *
 * @param status QUEUED, SENDING, SENT, DELIVERED, READ, FAILED or UNCERTAIN
 * @param errorCode stable code when FAILED (for example META_131047, outside the 24-hour window)
 */
public record OutboxItem(
    UUID id,
    UUID conversationId,
    String status,
    String body,
    String errorCode,
    UUID requestedBy,
    Instant createdAt,
    Instant sentAt) {
  @Override
  public String toString() {
    return "OutboxItem[id=" + id + ", status=" + status + "]";
  }
}
