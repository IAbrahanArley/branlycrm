package br.com.branlycrm.task.domain;

import java.time.Instant;
import java.util.UUID;

/** A task of the clinic as stored; DONE ones carry who completed them and when. */
public record Task(
    UUID id,
    TaskFields fields,
    UUID contactId,
    UUID leadId,
    TaskStatus status,
    Instant completedAt,
    UUID completedBy,
    String completionNote,
    Instant createdAt,
    UUID createdBy,
    long version) {
  public enum TaskStatus {
    OPEN,
    DONE
  }

  public Task {
    if (id == null
        || fields == null
        || contactId == null
        || status == null
        || createdAt == null
        || createdBy == null
        || version < 0
        || (status == TaskStatus.DONE) != (completedAt != null && completedBy != null)) {
      throw new IllegalArgumentException("Incomplete task");
    }
  }

  @Override
  public String toString() {
    return "Task[id=" + id + ", status=" + status + "]";
  }
}
