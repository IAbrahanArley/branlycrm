package br.com.branlycrm.pipeline.persistence;

import br.com.branlycrm.pipeline.domain.Lead;
import br.com.branlycrm.pipeline.domain.LeadStatus;
import br.com.branlycrm.pipeline.domain.Temperature;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

/**
 * A row of leads (tenant migration V6). Rows are created by the repository's insert-or-existing
 * statement; the entity changes stage, temperature, responsible and closure only, matching the
 * runtime's column grants.
 */
@Entity
@Table(name = "leads")
@DynamicUpdate
public class LeadEntity {
  @Id
  @Column(name = "id", updatable = false)
  private UUID id;

  @Column(name = "contact_id", nullable = false, updatable = false, insertable = false)
  private UUID contactId;

  @Column(name = "pipeline_id", nullable = false, updatable = false, insertable = false)
  private UUID pipelineId;

  @Column(name = "stage_id", nullable = false)
  private UUID stageId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 8)
  private LeadStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "temperature", length = 10)
  private Temperature temperature;

  @Column(name = "responsible_id")
  private UUID responsibleId;

  @Column(name = "loss_reason_id")
  private UUID lossReasonId;

  @Column(name = "opened_at", nullable = false, updatable = false, insertable = false)
  private Instant openedAt;

  @Column(name = "closed_at")
  private Instant closedAt;

  @Column(name = "created_by", updatable = false, insertable = false)
  private UUID createdBy;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by")
  private UUID updatedBy;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected LeadEntity() {}

  public UUID id() {
    return id;
  }

  public long version() {
    return version;
  }

  public UUID contactId() {
    return contactId;
  }

  public LeadStatus status() {
    return status;
  }

  public UUID stageId() {
    return stageId;
  }

  public UUID pipelineId() {
    return pipelineId;
  }

  public Temperature temperature() {
    return temperature;
  }

  public UUID responsibleId() {
    return responsibleId;
  }

  public void moveTo(UUID stage, UUID actor, Instant now) {
    this.stageId = stage;
    touch(actor, now);
  }

  public void changeTemperature(Temperature value, UUID actor, Instant now) {
    this.temperature = value;
    touch(actor, now);
  }

  public void assign(UUID responsible, UUID actor, Instant now) {
    this.responsibleId = responsible;
    touch(actor, now);
  }

  public void close(LeadStatus outcome, UUID stage, UUID lossReason, UUID actor, Instant now) {
    this.status = outcome;
    this.stageId = stage;
    this.lossReasonId = lossReason;
    this.closedAt = now;
    touch(actor, now);
  }

  public Lead toDomain() {
    return new Lead(
        id,
        contactId,
        stageId,
        status,
        temperature,
        responsibleId,
        lossReasonId,
        openedAt,
        closedAt,
        version);
  }

  private void touch(UUID actor, Instant now) {
    this.updatedAt = now;
    this.updatedBy = actor;
  }
}
