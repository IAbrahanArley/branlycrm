package br.com.branlycrm.api.identity;
import java.util.UUID;
public record UserAccount(UUID subjectId, String email, String displayName,
                          String passwordHash, boolean active, boolean emailVerified, long authVersion) {}