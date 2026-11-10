package br.com.branlycrm.pipeline.dto.request;

/** Opens a lead for a contact; temperature and responsible are optional. */
public record OpenLeadRequest(String contactId, String temperature, String responsibleId) {}
