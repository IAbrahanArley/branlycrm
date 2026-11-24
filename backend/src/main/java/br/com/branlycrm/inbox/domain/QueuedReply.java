package br.com.branlycrm.inbox.domain;

import java.util.UUID;

/**
 * A reply in the outbox and the central channel to signal (CRM-084a).
 *
 * @param created false when the same idempotency key had already queued it
 */
public record QueuedReply(OutboxItem item, UUID channelId, boolean created) {}
