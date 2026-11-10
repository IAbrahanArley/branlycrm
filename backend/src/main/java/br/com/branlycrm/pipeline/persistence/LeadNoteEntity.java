package br.com.branlycrm.pipeline.persistence;

import br.com.branlycrm.pipeline.domain.LeadNote;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** A note of lead_notes (tenant migration V7): inserted once, never updated. */
@Entity
@Table(name = "lead_notes")
@Immutable
public class LeadNoteEntity {
  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "lead_id", nullable = false)
  private UUID leadId;

  @Column(name = "body", nullable = false, length = 2000)
  private String body;

  @Column(name = "author_id", nullable = false)
  private UUID authorId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /** Write order, assigned by the database. */
  @Column(name = "seq", insertable = false, updatable = false)
  private Long seq;

  protected LeadNoteEntity() {}

  public LeadNoteEntity(UUID leadId, String body, UUID authorId, Instant createdAt) {
    this.id = UUID.randomUUID();
    this.leadId = leadId;
    this.body = body;
    this.authorId = authorId;
    this.createdAt = createdAt;
  }

  public LeadNote toDomain() {
    return new LeadNote(id, leadId, body, authorId, createdAt);
  }
}
