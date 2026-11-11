package br.com.branlycrm.task.exceptions;

/** Task outcome; the code is TASK_ plus the reason. HTTP translation belongs to the advice. */
public final class TaskException extends RuntimeException {
  public enum Reason {
    /** Missing in this clinic; another clinic's id answers the same. */
    NOT_FOUND,
    /** The task changed since it was read. */
    STALE,
    /** A completed task is reopened before it is edited or completed again. */
    DONE,
    /** The contact is missing or archived. */
    CONTACT_UNAVAILABLE,
    /** The lead is missing or belongs to another contact. */
    LEAD_MISMATCH,
    /** The responsible is not an active member who works tasks. */
    RESPONSIBLE_INVALID
  }

  private final Reason reason;

  public TaskException(Reason reason) {
    super("TASK_" + reason.name());
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
