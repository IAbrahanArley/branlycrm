package br.com.branlycrm.onboarding.dto.response;

import java.time.Instant;
import java.util.UUID;

/** Pending invitation of the selected clinic; expired ones stay listed to be resent or revoked. */
public record TeamInvitationView(
    UUID invitationId, String email, String role, Instant expiresAt, boolean expired) {}
