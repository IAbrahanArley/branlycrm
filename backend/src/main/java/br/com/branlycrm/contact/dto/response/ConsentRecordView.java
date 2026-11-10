package br.com.branlycrm.contact.dto.response;

import java.time.Instant;
import java.util.UUID;

/** One consent history entry; {@code recordedBy} is null only for WhatsApp requests. */
public record ConsentRecordView(
    UUID id, String channel, String status, String source, Instant recordedAt, UUID recordedBy) {}
