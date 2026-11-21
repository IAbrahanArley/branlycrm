package br.com.branlycrm.whatsapp.domain;

import br.com.branlycrm.contact.domain.ContactFields;
import br.com.branlycrm.pipeline.domain.LeadOrigin;
import java.time.Instant;
import java.util.Objects;

/**
 * A message ready to be stored (CRM-082b): the contact it belongs to, the content kept for the
 * inbox and the origin detected for a new lead. Content and phone never appear in {@link
 * #toString()}.
 *
 * @param contact the contact's canonical phone and, for a new contact, the WhatsApp profile name
 * @param fromBusinessApp an echo of a message the clinic sent from the WhatsApp Business app
 * @param body text, caption or button title, already without NUL and capped
 * @param origin detected for a new lead or a touch; unidentified when nothing was found
 */
public record InboundMessage(
    String wamid,
    ContactFields contact,
    boolean fromBusinessApp,
    String type,
    String body,
    String mediaId,
    String mediaMime,
    Instant sentAt,
    LeadOrigin origin) {
  public static final int BODY_MAX = 4096;

  public InboundMessage {
    Objects.requireNonNull(wamid, "wamid");
    Objects.requireNonNull(contact, "contact");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(sentAt, "sentAt");
    Objects.requireNonNull(origin, "origin");
    if (!type.matches("[a-z_]{1,16}")) throw new IllegalArgumentException("type");
    if (body != null && body.length() > BODY_MAX) throw new IllegalArgumentException("body");
  }

  @Override
  public String toString() {
    return "InboundMessage[type=" + type + ", fromBusinessApp=" + fromBusinessApp + "]";
  }
}
