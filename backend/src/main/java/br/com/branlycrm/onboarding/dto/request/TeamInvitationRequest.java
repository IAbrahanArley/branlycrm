package br.com.branlycrm.onboarding.dto.request;

/**
 * Invitation to the selected clinic: address and clinic role (OWNER, MANAGER, ATTENDANT, VIEWER).
 */
public record TeamInvitationRequest(String email, String role) {}
