package br.com.branlycrm.timeline.exceptions;

/** The contact is missing in this clinic; another clinic's id answers the same. */
public final class TimelineException extends RuntimeException {
  public TimelineException() {
    super("CONTACT_NOT_FOUND");
  }
}
