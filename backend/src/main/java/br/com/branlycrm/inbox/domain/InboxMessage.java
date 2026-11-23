package br.com.branlycrm.inbox.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A message of a conversation as the inbox shows it (CRM-083). Meta's ids (wamid, media id) stay in
 * the database; the file of a media comes with CRM-084.
 *
 * @param seq arrival order inside the clinic, the order of the history and of the cursors
 * @param direction IN (from the contact) or OUT (sent by the clinic)
 * @param sentByApp an OUT message sent from the WhatsApp Business app, not from the CRM
 * @param sentAt Meta's time of the message (seconds), for display
 */
public record InboxMessage(
    UUID id,
    long seq,
    String direction,
    boolean sentByApp,
    String type,
    String body,
    String mediaMime,
    Instant sentAt) {}
