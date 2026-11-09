package br.com.branlycrm.identity.mfa.dto.request;

/** Either the six-digit code or one recovery code, never both. Never logged. */
public record MfaVerifyRequest(String code, String recoveryCode) {
  @Override
  public String toString() {
    return "MfaVerifyRequest[redacted]";
  }
}
