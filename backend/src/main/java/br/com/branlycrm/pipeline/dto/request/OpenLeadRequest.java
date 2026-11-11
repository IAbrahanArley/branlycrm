package br.com.branlycrm.pipeline.dto.request;

/**
 * Opens a lead for a contact; temperature, responsible and origin are optional. Without an origin
 * the lead starts as UNIDENTIFIED.
 */
public record OpenLeadRequest(
    String contactId, String temperature, String responsibleId, OriginRequest origin) {}
