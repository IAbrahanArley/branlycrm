package br.com.branlycrm.inbox.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * A conversation of the inbox (CRM-083).
 *
 * @param status WAITING or IN_SERVICE
 * @param awaitingReply the latest message came from the contact
 * @param preview the latest message's text, shortened, or null for media without caption
 * @param contactArchived the contact was archived: show it, its messages still arrive
 * @param version for the optimistic lock of release, assignee and needs-human
 */
public record ConversationView(
    UUID id,
    UUID contactId,
    String contactName,
    String contactPhone,
    String status,
    UUID assigneeId,
    Instant assignedAt,
    boolean needsHuman,
    boolean awaitingReply,
    Instant lastMessageAt,
    Instant lastInboundAt,
    String lastMessageType,
    String preview,
    boolean contactArchived,
    long version) {}
