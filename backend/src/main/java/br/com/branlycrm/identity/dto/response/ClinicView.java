package br.com.branlycrm.identity.dto.response;

import java.util.UUID;

/** Clinic the subject may select; the role applies to this clinic only. */
public record ClinicView(UUID tenantId, String clinicName, String role) {}
