package br.com.branlycrm.pipeline.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One entry of a sale's history: CREATED and CORRECTED carry the values after the change; CORRECTED
 * also the previous ones; CANCELLED only the reason.
 */
public record SaleEventView(
    UUID id,
    String type,
    UUID procedureId,
    UUID professionalId,
    Long amountCents,
    LocalDate soldOn,
    String paymentMethod,
    UUID previousProcedureId,
    UUID previousProfessionalId,
    Long previousAmountCents,
    LocalDate previousSoldOn,
    String previousPaymentMethod,
    String reason,
    UUID actorId,
    Instant occurredAt) {
  @Override
  public String toString() {
    return "SaleEventView[id=" + id + "]";
  }
}
