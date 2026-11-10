package br.com.branlycrm.pipeline.exceptions;

import java.util.UUID;

/** Lead outcome; HTTP translation belongs to the controller advice (CRM-032). */
public final class LeadException extends RuntimeException {
  public enum Reason {
    /** Missing in this clinic; another clinic's id answers the same. */
    NOT_FOUND,
    /** The contact already has an open lead (its id is carried). */
    ALREADY_OPEN,
    /** The lead changed since it was read. */
    STALE,
    /** The lead is won or lost; a new lead is opened instead. */
    CLOSED,
    /** The contact is missing or archived. */
    CONTACT_UNAVAILABLE,
    /** The stage is missing, archived, or one a lead cannot be moved to by hand. */
    STAGE_NOT_ALLOWED,
    /** The loss reason is missing or archived. */
    LOSS_REASON_NOT_FOUND,
    /** The responsible is not an active member who works leads. */
    RESPONSIBLE_INVALID
  }

  private final Reason reason;
  private final UUID existingLeadId;

  public LeadException(Reason reason) {
    this(reason, null);
  }

  public LeadException(Reason reason, UUID existingLeadId) {
    super("LEAD_" + reason.name());
    this.reason = reason;
    this.existingLeadId = existingLeadId;
  }

  public Reason reason() {
    return reason;
  }

  /** The open lead already held by the contact (ALREADY_OPEN). */
  public UUID existingLeadId() {
    return existingLeadId;
  }
}
