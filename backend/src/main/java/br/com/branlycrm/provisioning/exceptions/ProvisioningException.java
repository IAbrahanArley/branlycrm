package br.com.branlycrm.provisioning.exceptions;

/**
 * Failure with a stable diagnostic code that is safe to persist and log. The cause is kept for
 * debugging in-process but never written to the task row or the log line.
 */
public final class ProvisioningException extends RuntimeException {
  private final String code;

  public ProvisioningException(String code) {
    this(code, null);
  }

  public ProvisioningException(String code, Throwable cause) {
    super(code, cause, false, false);
    if (code == null || !code.matches("[A-Z_]{1,60}")) {
      throw new IllegalArgumentException("Invalid provisioning code");
    }
    this.code = code;
  }

  public String code() {
    return code;
  }
}
