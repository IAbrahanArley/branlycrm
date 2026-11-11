package br.com.branlycrm.task.dto.request;

/**
 * A task. {@code dueAt} is an ISO-8601 instant with offset; {@code contactId} and {@code leadId}
 * are set on creation only; {@code version} is required on edits.
 */
public record TaskRequest(
    String title,
    String description,
    String dueAt,
    String responsibleId,
    String contactId,
    String leadId,
    Long version) {
  @Override
  public String toString() {
    return "TaskRequest[]";
  }
}
