package br.com.branlycrm.onboarding.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * A clinic created by an agency. {@code created} is false when the same request had already created
 * it (no e-mail is sent again). {@code delivery} is SENT, FAILED or NOT_SENT (replay).
 */
public record AgencyClinicView(
    UUID tenantId,
    String clinicName,
    String status,
    String ownerEmail,
    Instant ownerInvitationExpiresAt,
    String delivery,
    boolean created) {}
