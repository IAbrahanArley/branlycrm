package br.com.branlycrm.whatsapp.secrets;

/** Sealing or opening a channel secret failed; the message is a stable code, never key material. */
public final class ChannelSecretException extends RuntimeException {
  public enum Reason {
    /** No key of the needed kind or version is mounted in this process. */
    NOT_CONFIGURED,
    /** The sealed value is malformed, tampered, or belongs to another channel or purpose. */
    INVALID
  }

  private final Reason reason;

  public ChannelSecretException(Reason reason) {
    super("WHATSAPP_SECRET_" + reason.name());
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
