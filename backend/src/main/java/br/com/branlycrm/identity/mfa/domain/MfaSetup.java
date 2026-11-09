package br.com.branlycrm.identity.mfa.domain;

/**
 * Result of a setup: the base32 secret and its otpauth URI, shown once, and the nonce of the staged
 * secret, which stays on the server (session) so only the setup of this session can be confirmed.
 */
public record MfaSetup(String secret, String otpauthUri, byte[] pendingNonce) {
  @Override
  public byte[] pendingNonce() {
    return pendingNonce.clone();
  }

  @Override
  public String toString() {
    return "MfaSetup[redacted]";
  }
}
