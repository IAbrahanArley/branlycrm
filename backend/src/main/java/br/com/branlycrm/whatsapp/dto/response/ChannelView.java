package br.com.branlycrm.whatsapp.dto.response;

import java.time.Instant;

/**
 * The clinic's WhatsApp channel as its managers see it (CRM-081b). Never a token, PIN, account or
 * number id.
 *
 * @param status NONE, CONNECTING, ACTIVE, FAILED or DISCONNECTING
 * @param failureCode stable code when FAILED (for example TOKEN_INVALID, NUMBER_IN_USE)
 */
public record ChannelView(
    String status,
    String displayPhoneNumber,
    String failureCode,
    Instant connectedAt,
    Instant updatedAt) {
  public static ChannelView none() {
    return new ChannelView("NONE", null, null, null, null);
  }
}
