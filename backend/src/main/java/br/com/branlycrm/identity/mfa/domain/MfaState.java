package br.com.branlycrm.identity.mfa.domain;

/**
 * Second-factor state of an authenticated session, derived on every request from the proof in the
 * session, the confirmed factor and whether the account is obliged to have one. Never stored.
 */
public enum MfaState {
  /** Password checked; the account has a confirmed factor and this session has not proved it. */
  MFA_PENDING,
  /** The account must have a factor (owner or agency admin) and has none yet. */
  ENROLLMENT_REQUIRED,
  /** Factor proved in this session, or none required and none configured. */
  COMPLETE
}
