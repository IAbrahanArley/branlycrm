package br.com.branlycrm.inbox.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A slice of a conversation's history, oldest first (CRM-083).
 *
 * @param before cursor for the older messages (null when there are none in this slice)
 * @param after cursor for the poll of new messages: send it back as {@code after}; it stays the
 *     same when nothing arrived
 */
public record MessagePageView(List<Message> items, String before, String after) {
  public MessagePageView {
    items = List.copyOf(items);
  }

  /**
   * @param direction IN (from the contact) or OUT (sent by the clinic)
   * @param sentByApp sent from the WhatsApp Business app
   * @param status delivery of a reply sent from the CRM (SENT, DELIVERED, READ, FAILED), or null
   * @param mediaStatus file of a received media: PENDING (downloading), STORED (link available),
   *     REJECTED (type or size not accepted) or FAILED; null without media
   */
  public record Message(
      UUID id,
      String direction,
      boolean sentByApp,
      String type,
      String body,
      String mediaMime,
      Instant sentAt,
      String status,
      String mediaStatus) {}
}
