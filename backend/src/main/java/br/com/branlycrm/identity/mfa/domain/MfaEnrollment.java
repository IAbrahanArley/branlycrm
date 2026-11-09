package br.com.branlycrm.identity.mfa.domain;

import java.util.List;

/**
 * A confirmed factor: the recovery codes, shown once; the proof for this session; and whether it
 * replaced an earlier factor, which revoked every session of the account.
 */
public record MfaEnrollment(List<String> recoveryCodes, MfaProof proof, boolean replaced) {
  public MfaEnrollment {
    recoveryCodes = List.copyOf(recoveryCodes);
  }

  @Override
  public String toString() {
    return "MfaEnrollment[replaced=" + replaced + "]";
  }
}
