package br.com.branlycrm.catalog.persistence;

import br.com.branlycrm.catalog.domain.Professional;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

/** A row of professionals (tenant migration V8). */
@Entity
@Table(name = "professionals")
@DynamicUpdate
public class ProfessionalEntity {
  @Id
  @Column(name = "id", updatable = false)
  private UUID id;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

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

  protected ProfessionalEntity() {}

  public ProfessionalEntity(String name, UUID actor, Instant now) {
    this.id = UUID.randomUUID();
    this.active = true;
    this.createdAt = now;
    this.createdBy = actor;
    rename(name, actor, now);
  }

  public long version() {
    return version;
  }

  public boolean active() {
    return active;
  }

  public void rename(String name, UUID actor, Instant now) {
    this.name = name;
    touch(actor, now);
  }

  public void setActive(boolean active, UUID actor, Instant now) {
    this.active = active;
    touch(actor, now);
  }

  public Professional toDomain() {
    return new Professional(id, name, active, version);
  }

  private void touch(UUID actor, Instant now) {
    this.updatedAt = now;
    this.updatedBy = actor;
  }
}
