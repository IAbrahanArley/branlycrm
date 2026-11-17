package br.com.branlycrm.whatsapp.domain;

import java.util.Objects;

/**
 * Where an inbound event goes (ADR-036): messages and statuses by the phone number id, account
 * events by the WhatsApp Business Account id ({@code entry.id}). The tenant always comes from the
 * central channel registry, never from the payload.
 *
 * @param phoneNumberId the number of the event, or null for account events
 * @param wabaId the account of the entry; for numbers it must match the channel's account
 * @param displayPhone for account events about one number, its display form; used only when the
 *     account has more than one live channel
 */
public record WebhookRoute(String phoneNumberId, String wabaId, String displayPhone) {
  public WebhookRoute {
    Objects.requireNonNull(wabaId, "wabaId");
  }

  public WebhookRoute(String phoneNumberId, String wabaId) {
    this(phoneNumberId, wabaId, null);
  }

  public boolean byNumber() {
    return phoneNumberId != null;
  }
}
