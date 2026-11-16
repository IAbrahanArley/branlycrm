package br.com.branlycrm.timeline.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry of the contact's timeline; fields that do not apply to the {@code kind} are null (see
 * {@code TimelineEntry}). {@code kind} plus {@code id} is the unique key: TASK_CREATED and
 * TASK_COMPLETED share the task id. Pages use OFFSET, so a client that appends pages deduplicates
 * by that key, since entries written meanwhile shift the next page.
 */
public record TimelineEntryView(
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
  @Override
  public String toString() {
    return "TimelineEntryView[kind=" + kind + ", id=" + id + "]";
  }
}
