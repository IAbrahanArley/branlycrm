package br.com.branlycrm.identity.dto.response;

import java.util.Map;
import java.util.UUID;

/**
 * Selected clinic with the subject's role and the scope of each permission (ALL, OWN, NONE). Only a
 * hint for the interface: the server authorizes every request.
 */
public record CurrentClinicView(UUID tenantId, String role, Map<String, String> permissions) {}
