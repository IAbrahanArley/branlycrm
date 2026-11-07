package br.com.branlycrm.onboarding.dto.request;

/**
 * New clinic in an agency portfolio. {@code requestId} is a UUID chosen by the client: repeating
 * the same request returns the clinic already created instead of a second one.
 */
public record AgencyClinicRequest(String requestId, String clinicName, String ownerEmail) {}
