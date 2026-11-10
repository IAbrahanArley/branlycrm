package br.com.branlycrm.pipeline.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A sale; only ACTIVE ones count as revenue. Send {@code version} back to correct or cancel. */
public record SaleView(
    UUID id,
    UUID leadId,
    UUID contactId,
    UUID procedureId,
    UUID professionalId,
    long amountCents,
    LocalDate soldOn,
    String paymentMethod,
    String status,
    String cancelReason,
    Instant cancelledAt,
    Instant createdAt,
    UUID createdBy,
    long version) {
  @Override
  public String toString() {
    return "SaleView[id=" + id + "]";
  }
}
