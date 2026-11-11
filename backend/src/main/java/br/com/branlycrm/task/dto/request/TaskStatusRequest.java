package br.com.branlycrm.task.dto.request;

/** Completes or reopens a task at its {@code version}; the note is optional on completion. */
public record TaskStatusRequest(Long version, String note) {
  /** The note is free text that may hold health data: kept out of logs. */
  @Override
  public String toString() {
    return "TaskStatusRequest[version=" + version + "]";
  }
}
