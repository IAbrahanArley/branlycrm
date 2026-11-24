package br.com.branlycrm.inbox.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A reply queued for sending (CRM-084a). The text never appears in {@link #toString()}.
 *
 * @param status QUEUED, SENDING, SENT, DELIVERED, READ, FAILED or UNCERTAIN
 * @param errorCode stable code when FAILED (for example META_131047, outside the 24-hour window)
 * @param body the text, or the optional caption of a file
 * @param kind TEXT or MEDIA (CRM-084b2)
 * @param contentType of the file (MEDIA only)
 * @param fileName of a document (MEDIA only, may be null)
 */
public record OutboxItem(
    UUID id,
    UUID conversationId,
    String status,
    String body,
    String errorCode,
    UUID requestedBy,
    Instant createdAt,
    Instant sentAt,
    String kind,
    String contentType,
    String fileName) {
  @Override
  public String toString() {
    return "OutboxItem[id=" + id + ", status=" + status + "]";
  }
}
