package br.com.branlycrm.inbox.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A message template copied from the clinic's WhatsApp account (CRM-085).
 *
 * @param status Meta's status: APPROVED, PENDING, REJECTED, PAUSED, DISABLED...
 * @param supported false for templates the CRM does not send (media header, buttons with
 *     parameters, authentication)
 * @param sendable approved and supported
 * @param headerParams the keys the header needs ({@code "1"} or a name), in order
 * @param bodyParams the keys the body needs, in order
 */
public record TemplateView(
    UUID id,
    String name,
    String language,
    String category,
    String status,
    boolean supported,
    boolean sendable,
    String headerText,
    String bodyText,
    String footerText,
    List<String> headerParams,
    List<String> bodyParams,
    Instant syncedAt) {}
