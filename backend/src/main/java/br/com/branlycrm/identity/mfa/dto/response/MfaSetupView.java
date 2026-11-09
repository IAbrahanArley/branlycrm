package br.com.branlycrm.identity.mfa.dto.response;

/** Shown once: the base32 secret and the otpauth URI for the QR code. Never logged nor stored. */
public record MfaSetupView(String secret, String otpauthUri) {
  @Override
  public String toString() {
    return "MfaSetupView[redacted]";
  }
}
