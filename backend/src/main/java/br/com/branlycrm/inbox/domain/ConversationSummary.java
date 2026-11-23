package br.com.branlycrm.inbox.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A conversation as the inbox lists it (CRM-083): who it is with, who serves it and the latest
 * message, shortened.
 *
 * @param status WAITING or IN_SERVICE
 * @param assigneeId global subject_id of the member serving it, or null
 * @param lastDirection IN or OUT, of the latest message
 * @param preview the latest message's text, at most {@link #PREVIEW_MAX} characters, or null
 * @param contactArchived the contact was archived; its messages still arrive (no lead is opened)
 */
public record ConversationSummary(
    UUID id,
    UUID contactId,
    String contactName,
    String contactPhone,
    String status,
    UUID assigneeId,
    Instant assignedAt,
    boolean needsHuman,
    Instant lastMessageAt,
    Instant lastInboundAt,
    String lastDirection,
    String lastType,
    String preview,
    long version,
    boolean contactArchived) {
  public static final int PREVIEW_MAX = 120;

  public ConversationSummary {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(status, "status");
  }

  /** The latest message came from the contact: someone should answer. */
  public boolean awaitingReply() {
    return "IN".equals(lastDirection);
  }
}
