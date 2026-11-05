package br.com.branlycrm.tenant.exceptions;

/** Team management outcome; the controller advice owns HTTP translation. */
public final class TeamException extends RuntimeException {
  public enum Reason {
    /** Target is not an active member of the selected clinic. */
    NOT_FOUND,
    /** Target is visible to the actor, but the actor's role may not manage it. */
    FORBIDDEN,
    /** The change would leave an active clinic without an owner. */
    LAST_OWNER
  }

  private final Reason reason;

  public TeamException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
