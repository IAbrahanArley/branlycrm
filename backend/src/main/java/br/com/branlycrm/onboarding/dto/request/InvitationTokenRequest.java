package br.com.branlycrm.onboarding.dto.request;

/** Token from the link fragment, sent in the body so it never lands in URLs or access logs. */
public record InvitationTokenRequest(String token) {
  @Override
  public String toString() {
    return "InvitationTokenRequest[redacted]";
  }
}
