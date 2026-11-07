package br.com.branlycrm.onboarding.exceptions;

/**
 * Business refusal of an invitation operation, without HTTP. {@link Reason#INVALID} covers unknown,
 * expired, used, revoked and no longer authorized invitations alike, so the answer never tells
 * which one it was.
 */
public final class InvitationException extends RuntimeException {
  public enum Reason {
    INVALID,
    LOGIN_REQUIRED,
    ACCOUNT_MISMATCH,
    MEMBER_ALREADY_ACTIVE,
    NOT_FOUND,
    SEND_LIMIT,
    FORBIDDEN
  }

  private final Reason reason;

  public InvitationException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
