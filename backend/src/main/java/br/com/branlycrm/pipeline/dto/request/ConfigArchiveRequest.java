package br.com.branlycrm.pipeline.dto.request;

/** Archive or unarchive; {@code version} is the funnel's for stages and ignored for reasons. */
public record ConfigArchiveRequest(Boolean archived, Long version) {}
