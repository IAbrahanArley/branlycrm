package br.com.branlycrm.agency.dto.response;

import java.util.UUID;

/** An agency the authenticated user administers. */
public record AgencyView(UUID agencyId, String name) {}
