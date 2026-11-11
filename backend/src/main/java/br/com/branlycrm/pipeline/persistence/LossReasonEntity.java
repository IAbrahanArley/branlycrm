package br.com.branlycrm.pipeline.persistence;

import br.com.branlycrm.pipeline.domain.LossReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** A loss reason (tenant migration V5), read here; CRM-036 writes it with native statements. */
@Entity
@Table(name = "loss_reasons")
@Immutable
public class LossReasonEntity {
  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "name", nullable = false, length = 60)
  private String name;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "seeded", nullable = false)
  private boolean seeded;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by")
  private UUID updatedBy;

  @Column(name = "archived_at")
  private Instant archivedAt;

  @Column(name = "archived_by")
  private UUID archivedBy;

  @Column(name = "version", nullable = false)
  private long version;

  protected LossReasonEntity() {}

  public LossReason toDomain() {
    return new LossReason(id, name, archivedAt != null, version);
  }
}
