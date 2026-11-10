package br.com.branlycrm.pipeline.exceptions;

/** Sale outcome; HTTP translation belongs to the controller advice (CRM-035). */
public final class SaleException extends RuntimeException {
  public enum Reason {
    /** Missing in this clinic; another clinic's id answers the same. */
    NOT_FOUND,
    /** The sale changed since it was read. */
    STALE,
    /** The sale is cancelled and can no longer change. */
    CANCELLED,
    /** The procedure is missing, or inactive for a new choice. */
    PROCEDURE_UNAVAILABLE,
    /** The professional is missing, or inactive for a new choice. */
    PROFESSIONAL_UNAVAILABLE
  }

  private final Reason reason;

  public SaleException(Reason reason) {
    super("SALE_" + reason.name());
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
