package br.com.branlycrm.pipeline.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** The clinic's funnel (tenant migration V5), read here; CRM-036 locks and versions it natively. */
@Entity
@Table(name = "pipelines")
@Immutable
public class PipelineEntity {
  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "name", nullable = false, length = 60)
  private String name;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by")
  private UUID updatedBy;

  @Column(name = "version", nullable = false)
  private long version;

  protected PipelineEntity() {}

  public UUID id() {
    return id;
  }

  public String name() {
    return name;
  }

  public long version() {
    return version;
  }
}
