package br.com.branlycrm.whatsapp.services;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.contact.domain.ContactFields;
import br.com.branlycrm.contact.domain.PhoneNumber;
import br.com.branlycrm.whatsapp.domain.InboundAction;
import br.com.branlycrm.whatsapp.domain.InboundHandler;
import br.com.branlycrm.whatsapp.domain.InboundKind;
import br.com.branlycrm.whatsapp.domain.InboundMessage;
import br.com.branlycrm.whatsapp.domain.OriginDetector;
import br.com.branlycrm.whatsapp.domain.Referral;
import br.com.branlycrm.whatsapp.domain.StoredEvent;
import java.time.Instant;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeType;

/**
 * Reads a stored inbound event (CRM-082b): only messages and echoes become writes; statuses and
 * account events are skipped until CRM-084 and CRM-081b. Pure: no database, so it runs inside the
 * clinic transaction. Nothing it reads reaches the log.
 */
final class InboundEventInterpreter implements InboundHandler {
  static final String ECHOES = "smb_message_echoes";
  static final String INVALID_PHONE = "INVALID_PHONE";
  static final String INVALID_PAYLOAD = "INVALID_PAYLOAD";
  static final String DEFERRED_STATUS = "DEFERRED_STATUS";
  static final String DEFERRED_ACCOUNT = "DEFERRED_ACCOUNT";

  private static final Pattern HIDDEN = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");
  private static final Pattern TYPE = Pattern.compile("[a-z_]{1,16}");
  private static final Pattern DIGITS = Pattern.compile("[0-9]{6,20}");

  private final JsonMapper json;

  InboundEventInterpreter(JsonMapper json) {
    this.json = json;
  }

  @Override
  public InboundAction decide(StoredEvent event) {
    // Kept with a stable code so CRM-084 (statuses) and CRM-081b (account) can find them.
    if (event.kind() == InboundKind.STATUS) return new InboundAction.Skip(DEFERRED_STATUS);
    if (event.kind() != InboundKind.MESSAGE) return new InboundAction.Skip(DEFERRED_ACCOUNT);
    JsonNode root;
    try {
      root = event.payload() == null ? null : json.readTree(event.payload());
    } catch (JacksonException malformed) {
      return new InboundAction.Skip(INVALID_PAYLOAD);
    }
    JsonNode message = root == null ? null : root.get("message");
    if (!isObject(message)) return new InboundAction.Skip(INVALID_PAYLOAD);
    boolean echo = ECHOES.equals(text(root.get("field")));
    String waId = text(message.get(echo ? "to" : "from"));
    String wamid = text(message.get("id"));
    if (wamid == null) return new InboundAction.Skip(INVALID_PAYLOAD);
    if (waId == null || !DIGITS.matcher(waId).matches()) {
      return new InboundAction.Skip(INVALID_PHONE);
    }
    PhoneNumber phone;
    try {
      phone = PhoneNumber.parse("+" + waId);
    } catch (InvalidInputException invalid) {
      return new InboundAction.Skip(INVALID_PHONE);
    }
    String type = text(message.get("type"));
    if (type == null || !TYPE.matcher(type).matches()) type = "unknown";
    JsonNode content = message.get(type);
    String body = clean(body(type, message, content));
    String mediaId = isObject(content) ? limited(text(content.get("id")), 128) : null;
    String mediaMime = isObject(content) ? limited(text(content.get("mime_type")), 100) : null;
    Referral referral = echo ? null : referral(message.get("referral"));
    return new InboundAction.Store(
        new InboundMessage(
            wamid,
            contact(phone, echo ? null : profileName(root, waId)),
            echo,
            type,
            body,
            mediaId,
            mediaMime,
            sentAt(message, event),
            echo ? OriginDetector.detect(null, null) : OriginDetector.detect(referral, body)));
  }

  private static String body(String type, JsonNode message, JsonNode content) {
    return switch (type) {
      case "text" -> isObject(content) ? text(content.get("body")) : null;
      case "image", "video", "document" -> isObject(content) ? text(content.get("caption")) : null;
      case "button" -> isObject(content) ? text(content.get("text")) : null;
      case "reaction" -> isObject(content) ? text(content.get("emoji")) : null;
      case "interactive" -> {
        if (!isObject(content)) yield null;
        String reply = text(content.path("button_reply").get("title"));
        yield reply != null ? reply : text(content.path("list_reply").get("title"));
      }
      default -> null;
    };
  }

  /** The profile name Meta sent for this number; a new contact needs a name. */
  private static String profileName(JsonNode root, String waId) {
    JsonNode contacts = root.get("contacts");
    if (contacts == null || contacts.getNodeType() != JsonNodeType.ARRAY) return null;
    String only = null;
    for (JsonNode contact : contacts) {
      String name = text(contact.path("profile").get("name"));
      if (waId.equals(text(contact.get("wa_id")))) return name;
      if (contacts.size() == 1) only = name;
    }
    return only;
  }

  /** The profile name, cleaned; the phone itself when there is no usable name. */
  private static ContactFields contact(PhoneNumber phone, String profileName) {
    String name =
        profileName == null
            ? null
            : HIDDEN.matcher(profileName).replaceAll(" ").strip().replaceAll(" {2,}", " ");
    if (name != null && !name.isEmpty()) {
      try {
        return new ContactFields(
            limited(name, ContactFields.NAME_MAX).strip(), phone, null, null, null);
      } catch (InvalidInputException refused) {
        // Falls back to the number below.
      }
    }
    return new ContactFields(phone.e164(), phone, null, null, null);
  }

  private static Referral referral(JsonNode node) {
    if (!isObject(node)) return null;
    return new Referral(
        text(node.get("source_type")),
        text(node.get("source_id")),
        text(node.get("headline")),
        text(node.get("ctwa_clid")));
  }

  private static Instant sentAt(JsonNode message, StoredEvent event) {
    String raw = text(message.get("timestamp"));
    if (raw != null && raw.matches("[0-9]{1,12}"))
      return Instant.ofEpochSecond(Long.parseLong(raw));
    return event.eventAt() != null ? event.eventAt() : event.receivedAt();
  }

  /** Without NUL (jsonb and text columns refuse it), capped at WhatsApp's own limit. */
  private static String clean(String text) {
    if (text == null) return null;
    String without = text.replace("\u0000", "");
    return without.isBlank() ? null : limited(without, InboundMessage.BODY_MAX);
  }

  /** At most {@code max} chars, never splitting a surrogate pair. */
  static String limited(String text, int max) {
    if (text == null || text.length() <= max) return text;
    int end = Character.isLowSurrogate(text.charAt(max)) ? max - 1 : max;
    return text.substring(0, end);
  }

  private static String text(JsonNode node) {
    return node != null && node.getNodeType() == JsonNodeType.STRING ? node.asText() : null;
  }

  private static boolean isObject(JsonNode node) {
    return node != null && node.getNodeType() == JsonNodeType.OBJECT;
  }
}
