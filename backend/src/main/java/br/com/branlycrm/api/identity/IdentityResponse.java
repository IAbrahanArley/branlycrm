package br.com.branlycrm.api.identity;
import java.util.UUID;
public record IdentityResponse(UUID subjectId, String email) {}