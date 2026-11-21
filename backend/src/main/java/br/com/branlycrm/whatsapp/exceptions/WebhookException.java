package br.com.branlycrm.whatsapp.exceptions;

/** Refusals of the WhatsApp webhook, without HTTP; the error handler maps each reason. */
public class WebhookException extends RuntimeException {
  public enum Reason {
    /** The app secret or the verification token file is missing. */
    NOT_CONFIGURED,
    /** Wrong mode, token or challenge on the verification request. */
    VERIFICATION_FAILED,
    /** Missing or wrong {@code X-Hub-Signature-256}: nothing was stored. */
    SIGNATURE_INVALID,
    /** Signed but not valid JSON. */
    MALFORMED,
    /** Body above the limit. */
    TOO_LARGE,
    /** Too many concurrent deliveries; Meta retries. */
    BUSY,
    /** A clinic database refused the write; Meta retries and deduplication makes it safe. */
    STORAGE_FAILED
  }

  private final Reason reason;

  public WebhookException(Reason reason) {
    super(reason.name());
    this.reason = reason;
  }

  public WebhookException(Reason reason, Throwable cause) {
    super(reason.name(), cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
