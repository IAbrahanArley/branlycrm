package br.com.branlycrm.tenant.exceptions;

/**
 * Clinic missing, inactive, or without an active membership for the subject. The causes are not
 * distinguished so a caller cannot probe which clinics exist. HTTP translation belongs to the
 * controller advice.
 */
public final class TenantAccessException extends RuntimeException {
  public TenantAccessException() {
    super("Acesso à clínica negado.");
  }
}
