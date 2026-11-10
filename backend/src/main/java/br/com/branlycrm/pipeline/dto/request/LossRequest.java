package br.com.branlycrm.pipeline.dto.request;

/** Closes the lead as lost; the reason is required (CRM-06). */
public record LossRequest(String lossReasonId, Long version) {}
