package br.com.branlycrm.pipeline.persistence;

import br.com.branlycrm.pipeline.domain.PaymentMethod;
import br.com.branlycrm.pipeline.domain.SaleEvent;
import br.com.branlycrm.pipeline.domain.SaleEvent.SaleEventType;
import br.com.branlycrm.pipeline.domain.SaleFields;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** A row of sale_events (tenant migration V9), never updated. */
@Entity
@Table(name = "sale_events")
@Immutable
public class SaleEventEntity {
  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "sale_id", nullable = false)
  private UUID saleId;

  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false, length = 12)
  private SaleEventType type;

  @Column(name = "procedure_id")
  private UUID procedureId;

  @Column(name = "professional_id")
  private UUID professionalId;

  @Column(name = "amount_cents")
  private Long amountCents;

  @Column(name = "sold_on")
  private LocalDate soldOn;

  @Enumerated(EnumType.STRING)
  @Column(name = "payment_method", length = 16)
  private PaymentMethod paymentMethod;

  @Column(name = "previous_procedure_id")
  private UUID previousProcedureId;

  @Column(name = "previous_professional_id")
  private UUID previousProfessionalId;

  @Column(name = "previous_amount_cents")
  private Long previousAmountCents;

  @Column(name = "previous_sold_on")
  private LocalDate previousSoldOn;

  @Enumerated(EnumType.STRING)
  @Column(name = "previous_payment_method", length = 16)
  private PaymentMethod previousPaymentMethod;

  @Column(name = "reason", length = 500)
  private String reason;

  @Column(name = "actor_id", nullable = false)
  private UUID actorId;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Column(name = "seq", insertable = false, updatable = false)
  private Long seq;

  protected SaleEventEntity() {}

  public SaleEventEntity(
      UUID saleId,
      SaleEventType type,
      SaleFields values,
      SaleFields previous,
      String reason,
      UUID actor,
      Instant now) {
    this.id = UUID.randomUUID();
    this.saleId = saleId;
    this.type = type;
    if (values != null) {
      this.procedureId = values.procedureId();
      this.professionalId = values.professionalId();
      this.amountCents = values.amountCents();
      this.soldOn = values.soldOn();
      this.paymentMethod = values.paymentMethod();
    }
    if (previous != null) {
      this.previousProcedureId = previous.procedureId();
      this.previousProfessionalId = previous.professionalId();
      this.previousAmountCents = previous.amountCents();
      this.previousSoldOn = previous.soldOn();
      this.previousPaymentMethod = previous.paymentMethod();
    }
    this.reason = reason;
    this.actorId = actor;
    this.occurredAt = now;
  }

  public SaleEvent toDomain() {
    SaleFields values =
        amountCents == null
            ? null
            : new SaleFields(procedureId, professionalId, amountCents, soldOn, paymentMethod);
    SaleFields previous =
        previousAmountCents == null
            ? null
            : new SaleFields(
                previousProcedureId,
                previousProfessionalId,
                previousAmountCents,
                previousSoldOn,
                previousPaymentMethod);
    return new SaleEvent(id, type, values, previous, reason, actorId, occurredAt);
  }
}
