package br.com.branlycrm.whatsapp.meta;

/**
 * A Graph API call failed. The code is stable and safe to log or store: never a token, URL, message
 * text or response body.
 */
public class MetaGraphException extends RuntimeException {
  public enum Kind {
    /** Network, timeout, rate limit or a server error: try again later. */
    TRANSIENT,
    /** The request itself was refused (invalid code, parameter, permission): do not retry. */
    PERMANENT,
    /** The access token is invalid, expired or revoked (error 190). */
    TOKEN_INVALID,
    /** The app id or secret is missing. */
    NOT_CONFIGURED
  }

  private final Kind kind;

  public MetaGraphException(Kind kind, String code) {
    super(code);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }

  /** Stable code, at most 40 characters of {@code [A-Z0-9_]}. */
  public String code() {
    return getMessage();
  }
}
