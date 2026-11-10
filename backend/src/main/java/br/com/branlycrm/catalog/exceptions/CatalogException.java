package br.com.branlycrm.catalog.exceptions;

/** Catalog outcome; HTTP translation belongs to the controller advice (CRM-034). */
public final class CatalogException extends RuntimeException {
  public enum Reason {
    /** Missing in this clinic; another clinic's id answers the same. */
    PROCEDURE_NOT_FOUND,
    /** Another procedure of this clinic has the same name, whatever the case. */
    PROCEDURE_DUPLICATE,
    /** The procedure changed since it was read. */
    PROCEDURE_STALE,
    /** The clinic already has the maximum number of procedures. */
    PROCEDURE_LIMIT,
    PROFESSIONAL_NOT_FOUND,
    PROFESSIONAL_DUPLICATE,
    PROFESSIONAL_STALE,
    PROFESSIONAL_LIMIT
  }

  private final Reason reason;

  public CatalogException(Reason reason) {
    super(reason.name());
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
