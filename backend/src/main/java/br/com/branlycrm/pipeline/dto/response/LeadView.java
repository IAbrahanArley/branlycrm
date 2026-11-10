package br.com.branlycrm.pipeline.dto.response;

import java.time.Instant;
import java.util.UUID;

/** A lead; {@code temperature} and {@code responsibleId} are null until set. */
public record LeadView(
    UUID id,
    UUID contactId,
    UUID stageId,
    String status,
    String temperature,
    UUID responsibleId,
    UUID lossReasonId,
    Instant openedAt,
    Instant closedAt,
    long version) {}
