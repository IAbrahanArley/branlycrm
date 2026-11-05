package br.com.branlycrm.tenant.secrets;

/** Secret store outcome. Messages never carry the secret value or file contents. */
public final class TenantSecretException extends RuntimeException {
  public enum Reason {
    INVALID,
    ALREADY_EXISTS,
    MISSING,
    VERSION_CONFLICT,
    UNAVAILABLE
  }

  private final Reason reason;

  public TenantSecretException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public TenantSecretException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
