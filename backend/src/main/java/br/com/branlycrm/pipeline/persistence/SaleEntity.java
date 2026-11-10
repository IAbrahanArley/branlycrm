package br.com.branlycrm.pipeline.persistence;

import br.com.branlycrm.pipeline.domain.PaymentMethod;
import br.com.branlycrm.pipeline.domain.Sale;
import br.com.branlycrm.pipeline.domain.SaleFields;
import br.com.branlycrm.pipeline.domain.SaleStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

/** A row of sales (tenant migration V9); only changed columns are written, as the grants allow. */
@Entity
@Table(name = "sales")
@DynamicUpdate
public class SaleEntity {
  @Id
  @Column(name = "id", updatable = false)
  private UUID id;

  @Column(name = "lead_id", nullable = false, updatable = false)
  private UUID leadId;

  @Column(name = "contact_id", nullable = false, updatable = false)
  private UUID contactId;

  @Column(name = "procedure_id", nullable = false)
  private UUID procedureId;

  @Column(name = "professional_id")
  private UUID professionalId;

  @Column(name = "amount_cents", nullable = false)
  private long amountCents;

  @Column(name = "sold_on", nullable = false)
  private LocalDate soldOn;

  @Enumerated(EnumType.STRING)
  @Column(name = "payment_method", length = 16)
  private PaymentMethod paymentMethod;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 10)
  private SaleStatus status;

  @Column(name = "cancel_reason", length = 500)
  private String cancelReason;

  @Column(name = "cancelled_at")
  private Instant cancelledAt;

  @Column(name = "cancelled_by")
  private UUID cancelledBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "created_by", nullable = false, updatable = false)
  private UUID createdBy;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by", nullable = false)
  private UUID updatedBy;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected SaleEntity() {}

  /** {@code fields.soldOn()} is already resolved. */
  public SaleEntity(UUID leadId, UUID contactId, SaleFields fields, UUID actor, Instant now) {
    this.id = UUID.randomUUID();
    this.leadId = leadId;
    this.contactId = contactId;
    this.status = SaleStatus.ACTIVE;
    this.createdAt = now;
    this.createdBy = actor;
    write(fields, actor, now);
  }

  public UUID id() {
    return id;
  }

  public UUID contactId() {
    return contactId;
  }

  public long version() {
    return version;
  }

  public SaleStatus status() {
    return status;
  }

  public SaleFields fields() {
    return new SaleFields(procedureId, professionalId, amountCents, soldOn, paymentMethod);
  }

  public void correct(SaleFields fields, UUID actor, Instant now) {
    write(fields, actor, now);
  }

  public void cancel(String reason, UUID actor, Instant now) {
    this.status = SaleStatus.CANCELLED;
    this.cancelReason = reason;
    this.cancelledAt = now;
    this.cancelledBy = actor;
    touch(actor, now);
  }

  public Sale toDomain() {
    return new Sale(
        id,
        leadId,
        contactId,
        fields(),
        status,
        cancelReason,
        cancelledAt,
        createdAt,
        createdBy,
        version);
  }

  private void write(SaleFields fields, UUID actor, Instant now) {
    this.procedureId = fields.procedureId();
    this.professionalId = fields.professionalId();
    this.amountCents = fields.amountCents();
    this.soldOn = fields.soldOn();
    this.paymentMethod = fields.paymentMethod();
    touch(actor, now);
  }

  private void touch(UUID actor, Instant now) {
    this.updatedAt = now;
    this.updatedBy = actor;
  }
}
