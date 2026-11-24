package br.com.branlycrm.inbox.exceptions;

/** Refusals of the inbox, without HTTP (CRM-083). */
public class InboxException extends RuntimeException {
  public enum Reason {
    /** Missing, or of another clinic (same answer). */
    NOT_FOUND,
    /** Another member already serves the conversation. */
    TAKEN,
    /** The conversation changed since it was read (version). */
    STALE,
    /** Nobody serves the conversation. */
    NOT_ASSIGNED,
    /** Releasing someone else's conversation needs INBOX_ASSIGN. */
    NOT_YOURS,
    /** The new assignee is not an active member who can reply. */
    ASSIGNEE_INVALID,
    /** More than 24 hours since the contact's last message: only templates (CRM-085). */
    WINDOW_CLOSED,
    /** The idempotency key was already used for another conversation. */
    IDEMPOTENCY_CONFLICT,
    /** The message has no media (or is of another conversation). */
    MEDIA_NOT_FOUND,
    /** The file is still being downloaded. */
    MEDIA_NOT_READY,
    /** The file was not accepted (type or size) or could not be downloaded. */
    MEDIA_REJECTED,
    /** The object storage is not configured or did not answer. */
    MEDIA_UNAVAILABLE
  }

  private final Reason reason;

  public InboxException(Reason reason) {
    super(reason.name());
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
