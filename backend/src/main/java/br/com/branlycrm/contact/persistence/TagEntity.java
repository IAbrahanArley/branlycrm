package br.com.branlycrm.contact.persistence;

import br.com.branlycrm.contact.domain.Tag;
import br.com.branlycrm.contact.domain.TagName;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

/** A row of tags (tenant migration V4); only the name is ever updated. */
@Entity
@Table(name = "tags")
@DynamicUpdate
public class TagEntity {
  @Id
  @Column(name = "id", updatable = false)
  private UUID id;

  @Column(name = "name", nullable = false, length = 50)
  private String name;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "created_by", nullable = false, updatable = false)
  private UUID createdBy;

  protected TagEntity() {}

  public TagEntity(TagName name, UUID actor, Instant now) {
    this.id = UUID.randomUUID();
    this.name = name.value();
    this.createdAt = now;
    this.createdBy = actor;
  }

  public void rename(TagName name) {
    this.name = name.value();
  }

  public Tag toDomain() {
    return new Tag(id, new TagName(name));
  }
}
