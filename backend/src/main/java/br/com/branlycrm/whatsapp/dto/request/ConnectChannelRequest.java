package br.com.branlycrm.whatsapp.dto.request;

/**
 * The Embedded Signup result from the browser (CRM-081b).
 *
 * @param code the single-use code, exchanged at once and never stored
 * @param phoneNumberId optional: the number chosen in the signup, only a selector the worker checks
 *     against the token's own account
 * @param coexistence the number keeps the WhatsApp Business app (no registration)
 */
public record ConnectChannelRequest(String code, String phoneNumberId, Boolean coexistence) {
  @Override
  public String toString() {
    return "ConnectChannelRequest[redacted]";
  }
}
