package br.com.branlycrm.contact.persistence;

import br.com.branlycrm.contact.domain.Contact;
import br.com.branlycrm.contact.domain.ContactCreatedVia;
import br.com.branlycrm.contact.domain.ContactFields;
import br.com.branlycrm.contact.domain.ContactSummary;
import br.com.branlycrm.contact.domain.PhoneNumber;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

/**
 * A row of contacts (tenant migration V4). Rows are created by the repository's insert-or-existing
 * statement; the entity only edits and archives. Creation columns are never written by Hibernate,
 * and only changed columns are, matching the runtime's column grants.
 */
@Entity
@Table(name = "contacts")
@DynamicUpdate
public class ContactEntity {
  @Id
  @Column(name = "id", updatable = false)
  private UUID id;

  @Column(name = "name", nullable = false, length = 150)
  private String name;

  @Column(name = "name_search", nullable = false, length = 150)
  private String nameSearch;

  @Column(name = "phone_e164", nullable = false, length = 16)
  private String phoneE164;

  @Column(name = "email", length = 254)
  private String email;

  @Column(name = "birth_date")
  private LocalDate birthDate;

  @Column(name = "notes", length = 2000)
  private String notes;

  @Enumerated(EnumType.STRING)
  @Column(
      name = "created_via",
      nullable = false,
      length = 16,
      updatable = false,
      insertable = false)
  private ContactCreatedVia createdVia;

  @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
  private Instant createdAt;

  @Column(name = "created_by", updatable = false, insertable = false)
  private UUID createdBy;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by")
  private UUID updatedBy;

  @Column(name = "archived_at")
  private Instant archivedAt;

  @Column(name = "archived_by")
  private UUID archivedBy;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected ContactEntity() {}

  public UUID id() {
    return id;
  }

  public long version() {
    return version;
  }

  public boolean archived() {
    return archivedAt != null;
  }

  /** Writes every editable field; the search column always follows the name. */
  public void edit(ContactFields fields, UUID actor, Instant now) {
    this.name = fields.name();
    this.nameSearch = fields.nameSearch();
    this.phoneE164 = fields.phone().e164();
    this.email = fields.email();
    this.birthDate = fields.birthDate();
    this.notes = fields.notes();
    touch(actor, now);
  }

  public void archive(UUID actor, Instant now) {
    if (archivedAt != null) return;
    this.archivedAt = now;
    this.archivedBy = actor;
    touch(actor, now);
  }

  /** Brings an archived contact back to the default list and to edition. */
  public void unarchive(UUID actor, Instant now) {
    if (archivedAt == null) return;
    this.archivedAt = null;
    this.archivedBy = null;
    touch(actor, now);
  }

  public Contact toDomain() {
    return new Contact(
        id,
        new ContactFields(name, new PhoneNumber(phoneE164), email, birthDate, notes),
        createdVia,
        createdAt,
        archivedAt,
        version);
  }

  /**
   * Without notes and birth date, for lists. The listed fields pass the same rules as the detail,
   * so a row written outside them is refused in both places.
   */
  public ContactSummary toSummary() {
    var listed = new ContactFields(name, new PhoneNumber(phoneE164), email, null, null);
    return new ContactSummary(
        id, listed.name(), listed.phone(), listed.email(), createdVia, archivedAt);
  }

  private void touch(UUID actor, Instant now) {
    this.updatedAt = now;
    this.updatedBy = actor;
  }
}
