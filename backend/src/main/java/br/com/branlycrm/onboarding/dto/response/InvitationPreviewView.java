package br.com.branlycrm.onboarding.dto.response;

import java.time.Instant;

/**
 * What the token holder sees before accepting. {@code requiresLogin} tells whether a verified
 * account already exists for the invited address, which the token holder is entitled to know.
 */
public record InvitationPreviewView(
    String kind, String targetName, String role, boolean requiresLogin, Instant expiresAt) {}
