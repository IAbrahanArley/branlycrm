package br.com.branlycrm.tenant.domain;

import java.util.UUID;

/** Active membership of a subject in an active clinic; the role is valid for that clinic only. */
public record ClinicMembership(UUID tenantId, String clinicName, ClinicRole role) {}
