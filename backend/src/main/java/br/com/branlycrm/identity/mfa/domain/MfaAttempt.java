package br.com.branlycrm.identity.mfa.domain;

/**
 * Outcome of reserving a code comparison or of a failed one (V14): whether it may go on (or, after
 * a failure, whether the account is still unlocked), the seconds left of a lock, and whether this
 * very call started the lock, which happens once per lock window. Ten attempts per window; each
 * consecutive lock doubles, from 15 minutes up to 24 hours, until a success.
 */
public record MfaAttempt(boolean allowed, long retrySeconds, boolean lockStarted) {}
