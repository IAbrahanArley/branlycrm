package br.com.branlycrm.identity.mfa.domain;

import java.util.UUID;

/**
 * Stored factor of an account: the confirmed secret (null before the first confirmation), the
 * pending one of a setup (null when none), whether that pending one is still fresh and, when the
 * account is locked, the seconds left. Database time decides freshness and locks.
 */
public record MfaFactor(
    UUID subjectId,
    SealedSecret secret,
    SealedSecret pending,
    boolean pendingFresh,
    long lockedSeconds) {

  public boolean confirmed() {
    return secret != null;
  }

  public boolean locked() {
    return lockedSeconds > 0;
  }

  @Override
  public String toString() {
    return "MfaFactor[subjectId=" + subjectId + ", confirmed=" + confirmed() + "]";
  }
}
