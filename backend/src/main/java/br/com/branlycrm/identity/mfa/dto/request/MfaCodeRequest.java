package br.com.branlycrm.identity.mfa.dto.request;

/** Current six-digit code of the authenticator app. Never logged. */
public record MfaCodeRequest(String code) {
  @Override
  public String toString() {
    return "MfaCodeRequest[redacted]";
  }
}
