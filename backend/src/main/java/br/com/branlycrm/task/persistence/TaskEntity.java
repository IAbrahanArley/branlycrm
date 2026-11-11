package br.com.branlycrm.task.persistence;

import br.com.branlycrm.task.domain.Task;
import br.com.branlycrm.task.domain.Task.TaskStatus;
import br.com.branlycrm.task.domain.TaskFields;
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

/** A row of tasks (tenant migration V12); only changed columns are written, as granted. */
@Entity
@Table(name = "tasks")
@DynamicUpdate
public class TaskEntity {
  @Id
  @Column(name = "id", updatable = false)
  private UUID id;

  @Column(name = "title", nullable = false, length = 200)
  private String title;

  @Column(name = "description", length = 2000)
  private String description;

  @Column(name = "due_at", nullable = false)
  private Instant dueAt;

  @Column(name = "responsible_id", nullable = false)
  private UUID responsibleId;

  @Column(name = "contact_id", nullable = false, updatable = false)
  private UUID contactId;

  @Column(name = "lead_id", updatable = false)
  private UUID leadId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 8)
  private TaskStatus status;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "completed_by")
  private UUID completedBy;

  @Column(name = "completion_note", length = 500)
  private String completionNote;

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

  protected TaskEntity() {}

  public TaskEntity(TaskFields fields, UUID contactId, UUID leadId, UUID actor, Instant now) {
    this.id = UUID.randomUUID();
    this.contactId = contactId;
    this.leadId = leadId;
    this.status = TaskStatus.OPEN;
    this.createdAt = now;
    this.createdBy = actor;
    edit(fields, actor, now);
  }

  public long version() {
    return version;
  }

  public TaskStatus status() {
    return status;
  }

  public void edit(TaskFields fields, UUID actor, Instant now) {
    this.title = fields.title();
    this.description = fields.description();
    this.dueAt = fields.dueAt();
    this.responsibleId = fields.responsibleId();
    touch(actor, now);
  }

  public void complete(String note, UUID actor, Instant now) {
    this.status = TaskStatus.DONE;
    this.completedAt = now;
    this.completedBy = actor;
    this.completionNote = note;
    touch(actor, now);
  }

  public void reopen(UUID actor, Instant now) {
    this.status = TaskStatus.OPEN;
    this.completedAt = null;
    this.completedBy = null;
    this.completionNote = null;
    touch(actor, now);
  }

  public Task toDomain() {
    return new Task(
        id,
        new TaskFields(title, description, dueAt, responsibleId),
        contactId,
        leadId,
        status,
        completedAt,
        completedBy,
        completionNote,
        createdAt,
        createdBy,
        version);
  }

  private void touch(UUID actor, Instant now) {
    this.updatedAt = now;
    this.updatedBy = actor;
  }
}
