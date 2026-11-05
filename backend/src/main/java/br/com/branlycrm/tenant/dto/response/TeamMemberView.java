package br.com.branlycrm.tenant.dto.response;

import java.util.UUID;

/** Member of the selected clinic, for team management screens; never credentials. */
public record TeamMemberView(
    UUID subjectId, String displayName, String email, String role, boolean active) {}
