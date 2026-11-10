package br.com.branlycrm.catalog.dto.request;

/** Professional name; {@code version} is required on renames and ignored on creation. */
public record ProfessionalRequest(String name, Long version) {}
