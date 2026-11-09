package br.com.branlycrm.tenant.exceptions;

/**
 * Failure reading or writing a clinic database through the clinic persistence unit. The message is
 * a stable code: SQL text and values stay in the cause, never in what reaches a client or a log.
 * Missing access is not reported here but as {@link TenantAccessException}. Integrity conflicts
 * (unique, check) get their own reason with the first table that needs one (CRM-020).
 */
public final class ClinicDataException extends RuntimeException {
  public enum Reason {
    /**
     * The clinic database lacks a table, column or row this version expects (migration pending).
     */
    SCHEMA_OUTDATED,
    /** A stored value breaks a domain rule; written outside the application. */
    INVALID_DATA,
    /** The connected database declares another clinic: a registry incident, never retried. */
    IDENTITY_MISMATCH,
    /** Connection, lock, timeout or any other database failure. */
    UNAVAILABLE
  }

  private final Reason reason;

  public ClinicDataException(Reason reason, Throwable cause) {
    super("CLINIC_DATA_" + reason.name(), cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
