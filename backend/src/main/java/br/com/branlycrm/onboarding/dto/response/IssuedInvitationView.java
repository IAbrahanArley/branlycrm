package br.com.branlycrm.onboarding.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * An invitation just issued or reissued. {@code delivery} is SENT or FAILED; a failed e-mail leaves
 * the invitation pending, to be resent.
 */
public record IssuedInvitationView(
    UUID invitationId, String email, String role, Instant expiresAt, String delivery) {}
