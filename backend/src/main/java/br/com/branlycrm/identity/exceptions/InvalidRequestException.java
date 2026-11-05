package br.com.branlycrm.identity.exceptions;

/** Malformed client input detected by a controller; translated to 400 with a stable message. */
public final class InvalidRequestException extends RuntimeException {
  public InvalidRequestException(String message) {
    super(message);
  }
}
