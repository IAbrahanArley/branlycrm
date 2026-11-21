package br.com.branlycrm.whatsapp.exceptions;

/** Refusals of the channel connection, without HTTP (CRM-081b). */
public class ChannelException extends RuntimeException {
  public enum Reason {
    /** The clinic already has a live channel: disconnect it first. */
    ALREADY_CONNECTED,
    /** No live channel to disconnect. */
    NOT_CONNECTED,
    /** A connection or disconnection of the channel is still running. */
    BUSY,
    /** Meta refused the signup code (expired, used or from another app). */
    CODE_INVALID,
    /** Meta did not answer; the person can try again. */
    META_UNAVAILABLE,
    /** App id, app secret or sealing key missing on the server. */
    NOT_CONFIGURED
  }

  private final Reason reason;

  public ChannelException(Reason reason) {
    super(reason.name());
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
