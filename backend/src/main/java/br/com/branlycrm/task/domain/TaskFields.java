package br.com.branlycrm.task.domain;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import java.time.Instant;
import java.util.UUID;

/**
 * Editable data of a task (CRM-07). The description is free text that may receive health data:
 * capped and never searched.
 */
public record TaskFields(String title, String description, Instant dueAt, UUID responsibleId) {
  public static final int TITLE_MAX = 200;
  public static final int DESCRIPTION_MAX = 2000;

  public TaskFields {
    title = TaskText.line(title, TITLE_MAX, "title");
    if (title == null) throw new InvalidInputException("title");
    description = TaskText.multiline(description, DESCRIPTION_MAX, "description");
    if (dueAt == null) throw new InvalidInputException("dueAt");
    if (responsibleId == null) throw new InvalidInputException("responsibleId");
  }

  /** Keeps the free text out of logs. */
  @Override
  public String toString() {
    return "TaskFields[dueAt=" + dueAt + "]";
  }
}
