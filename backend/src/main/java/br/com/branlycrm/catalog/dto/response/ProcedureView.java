package br.com.branlycrm.catalog.dto.response;

import java.util.UUID;

public record ProcedureView(
    UUID id,
    String name,
    long referencePriceCents,
    String category,
    Integer returnIntervalDays,
    boolean active,
    long version) {}
