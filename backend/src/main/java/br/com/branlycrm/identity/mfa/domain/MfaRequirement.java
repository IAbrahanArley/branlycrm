package br.com.branlycrm.identity.mfa.domain;

/**
 * Whether the account has a confirmed factor and whether it must have one (an active OWNER
 * membership in any clinic or an active agency membership), read on the current request.
 */
public record MfaRequirement(boolean enrolled, boolean required) {
  public MfaState stateWithoutProof() {
    if (enrolled) return MfaState.MFA_PENDING;
    return required ? MfaState.ENROLLMENT_REQUIRED : MfaState.COMPLETE;
  }
}
