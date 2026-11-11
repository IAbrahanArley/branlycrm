package br.com.branlycrm.pipeline.dto.request;

/**
 * A stage or loss reason name; {@code version} is the funnel's for stages, the reason's for
 * reasons.
 */
public record ConfigNameRequest(String name, Long version) {}
