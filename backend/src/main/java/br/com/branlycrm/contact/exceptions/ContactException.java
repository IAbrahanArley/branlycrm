package br.com.branlycrm.contact.exceptions;

import java.util.UUID;

/**
 * Contact outcome; HTTP translation belongs to the controller advice (CRM-021). Messages never
 * carry names, phones or e-mails.
 */
public final class ContactException extends RuntimeException {
  public enum Reason {
    /** Missing in this clinic; another clinic's id answers the same. */
    NOT_FOUND,
    /** The number belongs to another contact of this clinic, archived ones included. */
    DUPLICATE_PHONE,
    /** The contact changed since it was read. */
    STALE,
    /** The consent rules refuse the change (an import cannot undo an opt-out). */
    CONSENT_REFUSED,
    /** A tag with the same name already exists in this clinic. */
    DUPLICATE_TAG
  }

  private final Reason reason;
  private final UUID existingContactId;

  public ContactException(Reason reason) {
    this(reason, null);
  }

  public ContactException(Reason reason, UUID existingContactId) {
    super("CONTACT_" + reason.name());
    this.reason = reason;
    this.existingContactId = existingContactId;
  }

  public Reason reason() {
    return reason;
  }

  /** The contact already holding the number, when known (DUPLICATE_PHONE). */
  public UUID existingContactId() {
    return existingContactId;
  }
}
