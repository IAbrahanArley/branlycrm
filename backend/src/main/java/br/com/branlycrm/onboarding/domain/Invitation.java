package br.com.branlycrm.onboarding.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * An invitation row (V9). Clinic invitations carry a clinic role name; agency invitations carry
 * AGENCY_ADMIN. {@code issuerAgencyId} marks the first-owner invitation issued by an agency.
 */
public record Invitation(
    UUID invitationId,
    InvitationKind kind,
    UUID tenantId,
    UUID agencyId,
    String email,
    String role,
    UUID invitedBy,
    UUID issuerAgencyId,
    Instant expiresAt,
    Instant acceptedAt,
    Instant revokedAt) {

  public boolean pending() {
    return acceptedAt == null && revokedAt == null;
  }

  /** Usable means pending and not expired at {@code now}. */
  public boolean usableAt(Instant now) {
    return pending() && now.isBefore(expiresAt);
  }

  public boolean issuedByAgency() {
    return issuerAgencyId != null;
  }
}
