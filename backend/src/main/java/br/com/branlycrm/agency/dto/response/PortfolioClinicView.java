package br.com.branlycrm.agency.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * A clinic of the portfolio. {@code ownerInvitation}: PENDING, EXPIRED, ACCEPTED or NONE. No
 * patient data and no owner address: the agency sees only the registry.
 */
public record PortfolioClinicView(
    UUID tenantId, String clinicName, String status, String ownerInvitation, Instant createdAt) {}
