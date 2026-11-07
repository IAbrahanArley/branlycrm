package br.com.branlycrm.onboarding.dto.request;

/**
 * Acceptance of an invitation. Name and password are required only when no verified account exists
 * for the invited address. Never logged.
 */
public record InvitationAcceptRequest(String token, String displayName, String password) {
  @Override
  public String toString() {
    return "InvitationAcceptRequest[redacted]";
  }
}
