package br.com.branlycrm.pipeline.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * A lead; {@code temperature} and {@code responsibleId} are null until set. {@code origin} is where
 * it came from (CRM-090).
 */
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
    long version,
    LeadOriginView origin) {}
