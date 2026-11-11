package br.com.branlycrm.task.dto.response;

import java.time.Instant;
import java.util.UUID;

/** A task; {@code status} is OPEN or DONE. Send {@code version} back to change it. */
public record TaskView(
    UUID id,
    String title,
    String description,
    Instant dueAt,
    UUID responsibleId,
    UUID contactId,
    UUID leadId,
    String status,
    Instant completedAt,
    UUID completedBy,
    String completionNote,
    Instant createdAt,
    UUID createdBy,
    long version) {
  /** Keeps free text out of logs. */
  @Override
  public String toString() {
    return "TaskView[id=" + id + "]";
  }
}
