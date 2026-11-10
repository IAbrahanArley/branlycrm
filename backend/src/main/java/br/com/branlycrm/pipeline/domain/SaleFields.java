package br.com.branlycrm.pipeline.domain;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What a sale records (CRM-05): procedure and amount are required; professional and payment are
 * optional. {@code soldOn} null means "today in the clinic's time zone", resolved by the
 * repository; it is never in the future there.
 */
public record SaleFields(
    UUID procedureId,
    UUID professionalId,
    long amountCents,
    LocalDate soldOn,
    PaymentMethod paymentMethod) {
  /** R$ 10.000.000,00, the database check. */
  public static final long AMOUNT_MAX_CENTS = 1_000_000_000L;

  public static final LocalDate OLDEST = LocalDate.of(2000, 1, 1);

  public SaleFields {
    if (procedureId == null) throw new InvalidInputException("procedureId");
    if (amountCents <= 0 || amountCents > AMOUNT_MAX_CENTS) {
      throw new InvalidInputException("amountCents");
    }
    if (soldOn != null && soldOn.isBefore(OLDEST)) throw new InvalidInputException("soldOn");
  }

  public SaleFields on(LocalDate day) {
    return new SaleFields(procedureId, professionalId, amountCents, day, paymentMethod);
  }
}
