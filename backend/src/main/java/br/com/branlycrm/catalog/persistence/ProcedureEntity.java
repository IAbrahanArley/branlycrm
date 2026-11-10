package br.com.branlycrm.catalog.persistence;

import br.com.branlycrm.catalog.domain.Procedure;
import br.com.branlycrm.catalog.domain.ProcedureFields;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

/** A row of procedures (tenant migration V8). */
@Entity
@Table(name = "procedures")
@DynamicUpdate
public class ProcedureEntity {
  @Id
  @Column(name = "id", updatable = false)
  private UUID id;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  @Column(name = "reference_price_cents", nullable = false)
  private long referencePriceCents;

  @Column(name = "category", length = 60)
  private String category;

  @Column(name = "return_interval_days")
  private Integer returnIntervalDays;

  @Column(name = "active", nullable = false)
  private boolean active;

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

  protected ProcedureEntity() {}

  public ProcedureEntity(ProcedureFields fields, UUID actor, Instant now) {
    this.id = UUID.randomUUID();
    this.active = true;
    this.createdAt = now;
    this.createdBy = actor;
    edit(fields, actor, now);
  }

  public long version() {
    return version;
  }

  public boolean active() {
    return active;
  }

  public void edit(ProcedureFields fields, UUID actor, Instant now) {
    this.name = fields.name();
    this.referencePriceCents = fields.referencePriceCents();
    this.category = fields.category();
    this.returnIntervalDays = fields.returnIntervalDays();
    touch(actor, now);
  }

  public void setActive(boolean active, UUID actor, Instant now) {
    this.active = active;
    touch(actor, now);
  }

  public Procedure toDomain() {
    return new Procedure(
        id,
        new ProcedureFields(name, referencePriceCents, category, returnIntervalDays),
        active,
        version);
  }

  private void touch(UUID actor, Instant now) {
    this.updatedAt = now;
    this.updatedBy = actor;
  }
}
