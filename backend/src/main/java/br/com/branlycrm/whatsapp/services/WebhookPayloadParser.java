package br.com.branlycrm.whatsapp.services;

import br.com.branlycrm.whatsapp.domain.InboundEvent;
import br.com.branlycrm.whatsapp.domain.InboundKind;
import br.com.branlycrm.whatsapp.domain.RoutedEvent;
import br.com.branlycrm.whatsapp.domain.WebhookRoute;
import br.com.branlycrm.whatsapp.exceptions.WebhookException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeType;
import tools.jackson.databind.node.ObjectNode;

/**
 * Splits a signed WhatsApp Cloud API delivery into one event per message, status or account change
 * (ADR-036). Only routing data is read here; the content is stored as JSON for the worker.
 *
 * <ul>
 *   <li>Messages and echoes (coexistence) are deduplicated by {@code wamid}; statuses by {@code
 *       wamid} and status; anything else by the SHA-256 of the change.
 *   <li>Fields outside the allowlist are ignored, notably {@code history}: the history import of
 *       coexistence is refused (ADR-036).
 *   <li>Items without the identifiers they need are skipped, never stored half-routed.
 * </ul>
 */
final class WebhookPayloadParser {
  static final String OBJECT = "whatsapp_business_account";

  /** Fields read by the product; the app subscribes to these only. */
  static final Set<String> FIELDS =
      Set.of(
          "messages",
          "smb_message_echoes",
          "account_update",
          "account_review_update",
          "business_capability_update",
          "message_template_status_update",
          "message_template_quality_update",
          "template_category_update",
          "phone_number_name_update",
          "phone_number_quality_update");

  private static final Pattern ID = Pattern.compile("[0-9]{1,32}");
  private static final Pattern STATUS = Pattern.compile("[a-z_]{1,32}");
  private static final Pattern ESCAPED_NUL = Pattern.compile("(?<!\\\\)((?:\\\\\\\\)*)\\\\u0000");

  private final JsonMapper json;

  WebhookPayloadParser(JsonMapper json) {
    this.json = json;
  }

  /**
   * @throws WebhookException MALFORMED when the body is not a JSON object
   */
  Parsed parse(byte[] body) {
    JsonNode root;
    try {
      root = json.readTree(body);
    } catch (JacksonException malformed) {
      throw new WebhookException(WebhookException.Reason.MALFORMED);
    }
    if (root == null || root.getNodeType() != JsonNodeType.OBJECT) {
      throw new WebhookException(WebhookException.Reason.MALFORMED);
    }
    List<RoutedEvent> events = new ArrayList<>();
    int skipped = 0;
    if (!OBJECT.equals(text(root.get("object")))) return new Parsed(events, 0);
    for (JsonNode entry : array(root.get("entry"))) {
      String waba = text(entry.get("id"));
      JsonNode time = entry.get("time");
      String entryTime = time == null || time.isNull() ? "" : time.asText();
      if (waba == null || !ID.matcher(waba).matches()) {
        skipped++;
        continue;
      }
      for (JsonNode change : array(entry.get("changes"))) {
        String field = text(change.get("field"));
        JsonNode value = change.get("value");
        if (field == null || !FIELDS.contains(field) || !isObject(value)) {
          skipped++;
          continue;
        }
        skipped += split(waba, entryTime, field, change, (ObjectNode) value, events);
      }
    }
    return new Parsed(List.copyOf(events), skipped);
  }

  /** Returns how many items were skipped. */
  private int split(
      String waba,
      String entryTime,
      String field,
      JsonNode change,
      ObjectNode value,
      List<RoutedEvent> out) {
    String phone = text(value.path("metadata").get("phone_number_id"));
    if (phone != null && !ID.matcher(phone).matches()) return 1;
    // Number updates of a shared account name the number only by its display form.
    String display = phone == null ? text(value.get("display_phone_number")) : null;
    WebhookRoute route = new WebhookRoute(phone, waba, display);
    JsonNode messages = value.has("messages") ? value.get("messages") : value.get("message_echoes");
    JsonNode statuses = value.get("statuses");
    if (phone == null || (messages == null && statuses == null)) {
      if (("messages".equals(field) || "smb_message_echoes".equals(field)) && phone == null) {
        return 1;
      }
      out.add(new RoutedEvent(route, account(waba, entryTime, field, change)));
      return 0;
    }
    int skipped = 0;
    for (JsonNode message : array(messages)) {
      String wamid = text(message.get("id"));
      if (!usable(wamid)) {
        skipped++;
        continue;
      }
      ObjectNode payload = json.createObjectNode();
      payload.put("field", field);
      payload.set("metadata", value.get("metadata"));
      if (value.has("contacts")) payload.set("contacts", value.get("contacts"));
      payload.set("message", message);
      out.add(
          new RoutedEvent(
              route,
              new InboundEvent(
                  "m:" + sha256(wamid),
                  InboundKind.MESSAGE,
                  field,
                  wamid,
                  timestamp(message),
                  stored(payload))));
    }
    for (JsonNode status : array(statuses)) {
      String wamid = text(status.get("id"));
      String state = text(status.get("status"));
      if (!usable(wamid) || state == null || !STATUS.matcher(state).matches()) {
        skipped++;
        continue;
      }
      ObjectNode payload = json.createObjectNode();
      payload.put("field", field);
      payload.set("metadata", value.get("metadata"));
      payload.set("status", status);
      out.add(
          new RoutedEvent(
              route,
              new InboundEvent(
                  "s:" + sha256(wamid + "\n" + state),
                  InboundKind.STATUS,
                  field,
                  wamid,
                  timestamp(status),
                  stored(payload))));
    }
    return skipped;
  }

  /**
   * Account events rarely carry a timestamp of their own: the entry's account and time join the
   * hash, so the same change sent again days later is a new event, while Meta's retries (same
   * entry) stay duplicates.
   */
  private InboundEvent account(String waba, String entryTime, String field, JsonNode change) {
    String serialized = stored(change);
    return new InboundEvent(
        "h:" + sha256(waba + "\n" + entryTime + "\n" + serialized),
        InboundKind.ACCOUNT,
        field,
        null,
        null,
        serialized);
  }

  /**
   * JSON for {@code jsonb}, which refuses the NUL character: an escaped NUL (not preceded by an
   * escaping backslash) is dropped instead of failing the clinic transaction and every retry.
   */
  private String stored(JsonNode node) {
    return ESCAPED_NUL.matcher(json.writeValueAsString(node)).replaceAll("$1");
  }

  private static boolean usable(String wamid) {
    return wamid != null
        && !wamid.isBlank()
        && wamid.length() <= InboundEvent.MAX_WAMID
        && wamid.indexOf('\u0000') < 0;
  }

  /** Meta sends epoch seconds as a string; anything else is left empty. */
  private static Instant timestamp(JsonNode item) {
    String raw = text(item.get("timestamp"));
    if (raw == null || !raw.matches("[0-9]{1,12}")) return null;
    return Instant.ofEpochSecond(Long.parseLong(raw));
  }

  private static String text(JsonNode node) {
    return node != null && node.getNodeType() == JsonNodeType.STRING ? node.asText() : null;
  }

  private static boolean isObject(JsonNode node) {
    return node != null && node.getNodeType() == JsonNodeType.OBJECT;
  }

  private static Iterable<JsonNode> array(JsonNode node) {
    if (node == null || node.getNodeType() != JsonNodeType.ARRAY) return List.of();
    List<JsonNode> items = new ArrayList<>();
    node.forEach(items::add);
    return items;
  }

  static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException missing) {
      throw new IllegalStateException("SHA-256 unavailable", missing);
    }
  }

  /** The events of a delivery and how many items could not be routed. */
  record Parsed(List<RoutedEvent> events, int skipped) {}
}
