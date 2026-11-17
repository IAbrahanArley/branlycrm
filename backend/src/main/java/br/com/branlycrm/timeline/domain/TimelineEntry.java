package br.com.branlycrm.timeline.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One entry of a contact's timeline (CRM-023). {@code kind} says where it comes from:
 *
 * <ul>
 *   <li>LEAD_EVENT: {@code type} is the lead event (CREATED, STAGE_CHANGED, ...);
 *   <li>NOTE: {@code text} is the note;
 *   <li>SALE_EVENT: {@code type} is CREATED, CORRECTED or CANCELLED, {@code text} the reason;
 *   <li>TASK_CREATED and TASK_COMPLETED: {@code text} is the task title.
 * </ul>
 *
 * <p>{@code actorId} is a global subject_id, null only for WhatsApp automation. {@code text} may
 * hold health data written by the team, so it never goes to logs.
 */
public record TimelineEntry(
    String kind,
    UUID id,
    Instant occurredAt,
    UUID actorId,
    UUID leadId,
    UUID saleId,
    UUID taskId,
    String type,
    String fromValue,
    String toValue,
    UUID lossReasonId,
    Long amountCents,
    String text) {
  public TimelineEntry {
    Objects.requireNonNull(kind);
    Objects.requireNonNull(id);
    Objects.requireNonNull(occurredAt);
  }

  @Override
  public String toString() {
    return "TimelineEntry[kind=" + kind + ", id=" + id + "]";
  }
}
