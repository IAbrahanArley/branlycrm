package br.com.branlycrm.pipeline.dto.response;

import java.time.Instant;
import java.util.UUID;

/** A later arrival of the contact with its origin; {@code leadId} is the lead open then. */
public record OriginTouchView(UUID id, UUID leadId, LeadOriginView origin, Instant receivedAt) {}
