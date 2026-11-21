package br.com.branlycrm.whatsapp.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A pending inbound event read by the worker; the payload never appears in {@link #toString()}. */
public record StoredEvent(
    UUID id, InboundKind kind, String field, Instant eventAt, Instant receivedAt, String payload) {
  public StoredEvent {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(kind, "kind");
  }

  @Override
  public String toString() {
    return "StoredEvent[id=" + id + ", kind=" + kind + ", field=" + field + "]";
  }
}
