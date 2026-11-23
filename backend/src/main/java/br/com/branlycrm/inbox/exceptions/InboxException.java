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
    ASSIGNEE_INVALID
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
