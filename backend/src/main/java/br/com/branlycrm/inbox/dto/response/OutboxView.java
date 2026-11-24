package br.com.branlycrm.inbox.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * A reply in the outbox (CRM-084a).
 *
 * @param status QUEUED, SENDING, SENT, DELIVERED, READ, FAILED or UNCERTAIN. UNCERTAIN: it may have
 *     reached the contact; check WhatsApp before sending again (never resent automatically).
 * @param errorCode when FAILED, for example META_131047 (outside the 24-hour window)
 * @param text the text, or the optional caption of a file (null without caption)
 * @param kind TEXT or MEDIA (CRM-084b2)
 * @param contentType the detected type of the file (MEDIA only)
 * @param fileName the document name the patient sees (MEDIA only, may be null)
 */
public record OutboxView(
    UUID id,
    UUID conversationId,
    String status,
    String text,
    String errorCode,
    UUID requestedBy,
    Instant createdAt,
    Instant sentAt,
    String kind,
    String contentType,
    String fileName) {
  @Override
  public String toString() {
    return "OutboxView[id=" + id + ", status=" + status + "]";
  }
}
