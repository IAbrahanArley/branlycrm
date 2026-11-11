package br.com.branlycrm.pipeline.persistence;

import br.com.branlycrm.pipeline.domain.PipelineStage;
import br.com.branlycrm.pipeline.domain.StageKey;
import br.com.branlycrm.pipeline.domain.StageKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** A stage of the funnel (tenant migration V5), read here; CRM-036 writes it natively. */
@Entity
@Table(name = "pipeline_stages")
@Immutable
public class PipelineStageEntity {
  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "pipeline_id", nullable = false)
  private UUID pipelineId;

  @Column(name = "name", nullable = false, length = 40)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 8)
  private StageKind kind;

  @Enumerated(EnumType.STRING)
  @Column(name = "system_key", length = 16)
  private StageKey systemKey;

  @Column(name = "position")
  private Integer position;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "created_by")
  private UUID createdBy;

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

  protected PipelineStageEntity() {}

  public PipelineStage toDomain() {
    return new PipelineStage(id, name, kind, systemKey, position);
  }
}
