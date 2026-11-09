package br.com.branlycrm.identity.mfa.dto.response;

/**
 * Second-factor state of the session: MFA_PENDING (send a code to /verify), ENROLLMENT_REQUIRED
 * (set up a factor) or COMPLETE. {@code required}: the account must keep a factor.
 */
public record MfaStatusView(
    String state, boolean enrolled, boolean required, long recoveryCodesRemaining) {}
