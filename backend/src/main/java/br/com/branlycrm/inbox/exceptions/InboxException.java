package br.com.branlycrm.inbox.exceptions;

/** Refusals of the inbox, without HTTP (CRM-083). */
public class InboxException extends RuntimeException {
  public enum Reason {
    /** Missing, or of another clinic (same answer). */
    NOT_FOUND,
    /** Another member already serves the conversation. */
    TAKEN,
    /** The conversation changed since it was read (version). */
    STALE,
    /** Nobody serves the conversation. */
    NOT_ASSIGNED,
    /** Releasing someone else's conversation needs INBOX_ASSIGN. */
    NOT_YOURS,
    /** The new assignee is not an active member who can reply. */
    ASSIGNEE_INVALID,
    /** More than 24 hours since the contact's last message: only templates (CRM-085). */
    WINDOW_CLOSED,
    /** The idempotency key was already used for another conversation. */
    IDEMPOTENCY_CONFLICT,
    /** The message has no media (or is of another conversation). */
    MEDIA_NOT_FOUND,
    /** The file is still being downloaded. */
    MEDIA_NOT_READY,
    /** The file was not accepted (type or size) or could not be downloaded. */
    MEDIA_REJECTED,
    /** The object storage is not configured or did not answer. */
    MEDIA_UNAVAILABLE,
    /** A file to send of a type WhatsApp does not send, found by its first bytes (CRM-084b2). */
    MEDIA_TYPE_NOT_ALLOWED,
    /** A file to send above the limit of its type (CRM-084b2). */
    MEDIA_TOO_LARGE,
    /** No such template in the clinic (CRM-085). */
    TEMPLATE_NOT_FOUND,
    /** The template is not approved by Meta, or not supported by the CRM (CRM-085). */
    TEMPLATE_NOT_APPROVED,
    /** A marketing template to a contact who opted out of WhatsApp (CRM-085). */
    TEMPLATE_OPTED_OUT,
    /** The clinic reached its daily limit of templates (CRM-085). */
    TEMPLATE_LIMIT,
    /** The clinic has no active WhatsApp channel to copy templates from (CRM-085). */
    CHANNEL_NOT_CONNECTED
  }

  private final Reason reason;

  public InboxException(Reason reason) {
    super(reason.name());
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
