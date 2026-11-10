package br.com.branlycrm.pipeline.dto.response;

import java.time.Instant;
import java.util.UUID;

/** A timeline entry; {@code actorId} is null only for WhatsApp automation. */
public record LeadEventView(
    UUID id,
    String type,
    String fromValue,
    String toValue,
    UUID lossReasonId,
    UUID actorId,
    Instant occurredAt) {}
