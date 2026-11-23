package br.com.branlycrm.inbox.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * A reply in the outbox (CRM-084a).
 *
 * @param status QUEUED, SENDING, SENT, DELIVERED, READ, FAILED or UNCERTAIN. UNCERTAIN: it may have
 *     reached the contact; check WhatsApp before sending again (never resent automatically).
 * @param errorCode when FAILED, for example META_131047 (outside the 24-hour window)
 */
public record OutboxView(
    UUID id,
    UUID conversationId,
    String status,
    String text,
    String errorCode,
    UUID requestedBy,
    Instant createdAt,
    Instant sentAt) {
  @Override
  public String toString() {
    return "OutboxView[id=" + id + ", status=" + status + "]";
  }
}
