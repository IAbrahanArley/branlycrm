package br.com.branlycrm.agency.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A clinic as its agency sees it: registry data only, never anything from the clinic database.
 * {@code ownerInvitation} is PENDING, EXPIRED, ACCEPTED or NONE.
 */
public record PortfolioClinic(
    UUID tenantId, String clinicName, String status, String ownerInvitation, Instant createdAt) {}
