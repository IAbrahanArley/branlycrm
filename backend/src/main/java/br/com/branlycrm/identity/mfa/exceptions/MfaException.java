package br.com.branlycrm.identity.mfa.exceptions;

/** Refused second-factor operation; the HTTP translation belongs to MfaErrorHandler. */
public final class MfaException extends RuntimeException {
  public enum Reason {
    /** Wrong, reused or unknown code, TOTP or recovery alike. */
    CODE_INVALID,
    /** Too many wrong codes for the account; {@link #retryAfterSeconds()} says until when. */
    LOCKED,
    /** No pending setup of this session, or it expired or was replaced by another setup. */
    SETUP_EXPIRED,
    /** The account must keep its factor (owner or agency admin). */
    MANDATORY,
    /** The operation needs a confirmed factor and the account has none. */
    NOT_ENROLLED
  }

  private final Reason reason;
  private final long retryAfterSeconds;

  public MfaException(Reason reason) {
    this(reason, 0);
  }

  public MfaException(Reason reason, long retryAfterSeconds) {
    super("MFA_" + reason.name(), null, false, false);
    this.reason = reason;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public Reason reason() {
    return reason;
  }

  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
