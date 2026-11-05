package br.com.branlycrm.identity.exceptions;

/** The session has no selected clinic; clinic-scoped endpoints never read it from the URL. */
public final class ClinicNotSelectedException extends RuntimeException {
  public ClinicNotSelectedException() {
    super("Selecione uma clínica.");
  }
}
