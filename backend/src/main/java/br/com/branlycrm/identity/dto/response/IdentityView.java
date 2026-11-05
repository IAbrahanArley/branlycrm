package br.com.branlycrm.identity.dto.response;

import java.util.UUID;

/** Authenticated identity without credentials, hashes or auth version. */
public record IdentityView(UUID subjectId, String email) {}
