package br.com.branlycrm.pipeline.persistence;

import br.com.branlycrm.pipeline.domain.LeadEvent;
import br.com.branlycrm.pipeline.domain.LeadEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** One entry of lead_events (tenant migration V6): inserted once, never updated. */
@Entity
@Table(name = "lead_events")
@Immutable
public class LeadEventEntity {
  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "lead_id", nullable = false)
  private UUID leadId;

  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false, length = 24)
  private LeadEventType type;

  @Column(name = "from_value", length = 40)
  private String fromValue;

  @Column(name = "to_value", length = 40)
  private String toValue;

  @Column(name = "loss_reason_id")
  private UUID lossReasonId;

  @Column(name = "actor_id")
  private UUID actorId;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  /** Write order, assigned by the database. */
  @Column(name = "seq", insertable = false, updatable = false)
  private Long seq;

  protected LeadEventEntity() {}

  public LeadEventEntity(
      UUID leadId,
      LeadEventType type,
      String fromValue,
      String toValue,
      UUID lossReasonId,
      UUID actorId,
      Instant occurredAt) {
    this.id = UUID.randomUUID();
    this.leadId = leadId;
    this.type = type;
    this.fromValue = fromValue;
    this.toValue = toValue;
    this.lossReasonId = lossReasonId;
    this.actorId = actorId;
    this.occurredAt = occurredAt;
  }

  public LeadEvent toDomain() {
    return new LeadEvent(id, leadId, type, fromValue, toValue, lossReasonId, actorId, occurredAt);
  }
}
