package br.com.branlycrm.pipeline.dto.request;

/**
 * Sale values. {@code soldOn} is an ISO day (yyyy-MM-dd): optional on registration (today in the
 * clinic's time zone), required on corrections. {@code version} is the lead's on registration and
 * the sale's on corrections; {@code reason} is required on corrections only.
 */
public record SaleRequest(
    String procedureId,
    String professionalId,
    Long amountCents,
    String soldOn,
    String paymentMethod,
    Long version,
    String reason) {}
