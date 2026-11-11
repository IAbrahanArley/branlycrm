package br.com.branlycrm.task.domain;

import java.util.UUID;

/**
 * Filters of the task list, every one optional. {@code due} only matches open tasks, in the
 * clinic's time zone: OVERDUE is before now, TODAY from now to the end of the day, UPCOMING after
 * today. Without {@code due} nor {@code status}, open tasks are listed.
 */
public record TaskSearch(
    Due due, UUID responsibleId, UUID contactId, UUID leadId, Task.TaskStatus status) {
  public enum Due {
    OVERDUE,
    TODAY,
    UPCOMING
  }

  public Task.TaskStatus effectiveStatus() {
    if (due != null) return Task.TaskStatus.OPEN;
    return status == null ? Task.TaskStatus.OPEN : status;
  }
}
