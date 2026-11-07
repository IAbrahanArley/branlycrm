package br.com.branlycrm.onboarding.exceptions;

/** Business refusal when an agency creates a clinic or reissues its owner invitation, no HTTP. */
public final class AgencyClinicException extends RuntimeException {
  public enum Reason {
    IDEMPOTENCY_KEY_REUSED,
    PENDING_LIMIT,
    CLINIC_NOT_FOUND,
    CLINIC_HAS_OWNER,
    SEND_LIMIT
  }

  private final Reason reason;

  public AgencyClinicException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
