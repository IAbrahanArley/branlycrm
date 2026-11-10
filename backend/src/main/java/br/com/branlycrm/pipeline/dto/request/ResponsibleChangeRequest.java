package br.com.branlycrm.pipeline.dto.request;

/** The new responsible (subject id), or null to clear it, at the version read. */
public record ResponsibleChangeRequest(String responsibleId, Long version) {}
