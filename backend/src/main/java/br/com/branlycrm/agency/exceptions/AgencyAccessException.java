package br.com.branlycrm.agency.exceptions;

/**
 * Refusal of an agency operation, without HTTP. The same answer covers an agency that does not
 * exist, one the user is not an active admin of, and a suspended one (ADR-026).
 */
public final class AgencyAccessException extends RuntimeException {
  public AgencyAccessException() {
    super("Agência não encontrada.");
  }
}
