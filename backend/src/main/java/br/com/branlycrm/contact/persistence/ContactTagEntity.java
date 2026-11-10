package br.com.branlycrm.contact.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * A link between a contact and a tag (tenant migration V4). Links are inserted or deleted by the
 * repository with explicit statements; the entity maps them for queries and schema validation.
 */
@Entity
@Table(name = "contact_tags")
@IdClass(ContactTagEntity.Key.class)
@Immutable
public class ContactTagEntity {
  @Id
  @Column(name = "contact_id")
  private UUID contactId;

  @Id
  @Column(name = "tag_id")
  private UUID tagId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  protected ContactTagEntity() {}

  /** Composite key. */
  public record Key(UUID contactId, UUID tagId) implements Serializable {}
}
