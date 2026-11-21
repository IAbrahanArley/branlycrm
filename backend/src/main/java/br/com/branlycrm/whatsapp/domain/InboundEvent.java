package br.com.branlycrm.whatsapp.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One inbound WhatsApp event as the webhook stores it (ADR-036). The payload may carry personal and
 * health data, so it never appears in {@link #toString()}.
 *
 * @param dedupKey unique per clinic: a prefix and a SHA-256, never the raw identifiers
 * @param wamid the message id, required except for account events
 * @param eventAt Meta's timestamp, when the event has one
 * @param payload the JSON stored for the worker
 */
public record InboundEvent(
    String dedupKey,
    InboundKind kind,
    String field,
    String wamid,
    Instant eventAt,
    String payload) {
  public static final int MAX_WAMID = 256;

  public InboundEvent {
    Objects.requireNonNull(dedupKey, "dedupKey");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(payload, "payload");
    if (dedupKey.length() > 200) throw new IllegalArgumentException("dedupKey too long");
    if (kind != InboundKind.ACCOUNT && (wamid == null || wamid.isBlank())) {
      throw new IllegalArgumentException("wamid required");
    }
    if (wamid != null && wamid.length() > MAX_WAMID) {
      throw new IllegalArgumentException("wamid too long");
    }
  }

  @Override
  public String toString() {
    return "InboundEvent[kind=" + kind + ", field=" + field + ", dedupKey=" + dedupKey + "]";
  }
}
