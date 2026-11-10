package br.com.branlycrm.catalog.dto.request;

/** Procedure fields; {@code version} is required on edits and ignored on creation. */
public record ProcedureRequest(
    String name,
    Long referencePriceCents,
    String category,
    Integer returnIntervalDays,
    Long version) {}
