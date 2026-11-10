package br.com.branlycrm.contact.repositories;

import br.com.branlycrm.contact.domain.Tag;
import br.com.branlycrm.contact.domain.TagName;
import br.com.branlycrm.contact.exceptions.ContactException;
import br.com.branlycrm.contact.persistence.ContactEntity;
import br.com.branlycrm.contact.persistence.TagEntity;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.exceptions.ClinicDataException;
import br.com.branlycrm.tenant.persistence.ClinicPersistence;
import br.com.branlycrm.tenant.persistence.ClinicTenant;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.Session;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;

/** Manual tags of the clinic authorized by a {@link ClinicAccess} (PAC-06). */
@Repository
public class TagRepository {
  /** The catalog is returned whole, so it is kept small. */
  public static final int MAX_TAGS = 500;

  private final ClinicPersistence persistence;
  private final Supplier<Instant> now;

  public TagRepository(ClinicPersistence persistence, ObjectProvider<Clock> clock) {
    this.persistence = persistence;
    Clock source = clock.getIfAvailable(Clock::systemUTC);
    // PostgreSQL keeps microseconds: what is returned must equal what is stored.
    this.now = () -> source.instant().truncatedTo(ChronoUnit.MICROS);
  }

  /**
   * @throws ContactException DUPLICATE_TAG when the name exists, whatever its case
   */
  public Tag create(ClinicAccess access, TagName name) {
    Instant at = now.get();
    try {
      return persistence.inTransaction(
          ClinicTenant.of(access),
          session -> {
            long existing =
                session
                    .createSelectionQuery("select count(t) from TagEntity t", Long.class)
                    .getSingleResult();
            if (existing >= MAX_TAGS) throw new ContactException(ContactException.Reason.TAG_LIMIT);
            TagEntity row = new TagEntity(name, access.subjectId(), at);
            session.persist(row);
            session.flush();
            return row.toDomain();
          });
    } catch (ClinicDataException failure) {
      throw duplicate(failure);
    }
  }

  public List<Tag> list(ClinicAccess access) {
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session ->
            session
                .createSelectionQuery(
                    "from TagEntity t order by lower(t.name), t.id", TagEntity.class)
                .getResultList()
                .stream()
                .map(TagEntity::toDomain)
                .toList());
  }

  public Tag rename(ClinicAccess access, UUID id, TagName name) {
    try {
      return persistence.inTransaction(
          ClinicTenant.of(access),
          session -> {
            TagEntity row = requireTag(session, id);
            row.rename(name);
            session.flush();
            return row.toDomain();
          });
    } catch (ClinicDataException failure) {
      throw duplicate(failure);
    }
  }

  /** Deletes the tag and, through the foreign key, its links to contacts. */
  public void delete(ClinicAccess access, UUID id) {
    persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          int deleted =
              session
                  .createNativeMutationQuery("DELETE FROM tags WHERE id = :id")
                  .setParameter("id", id)
                  .executeUpdate();
          if (deleted == 0) throw new ContactException(ContactException.Reason.TAG_NOT_FOUND);
          return null;
        });
  }

  /**
   * Links the tag to the contact; linking twice is a no-op. Returns the contact's tags.
   *
   * @throws ContactException NOT_FOUND (contact), TAG_NOT_FOUND or ARCHIVED
   */
  public List<Tag> tag(ClinicAccess access, UUID contactId, UUID tagId) {
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          ContactEntity contact = requireContact(session, contactId);
          if (contact.archived()) throw new ContactException(ContactException.Reason.ARCHIVED);
          requireTag(session, tagId);
          session
              .createNativeMutationQuery(
                  """
                  INSERT INTO contact_tags (contact_id, tag_id, created_at, created_by)
                  VALUES (:contact, :tag, :at, :actor)
                  ON CONFLICT DO NOTHING
                  """)
              .setParameter("contact", contactId)
              .setParameter("tag", tagId)
              .setParameter("at", now.get())
              .setParameter("actor", access.subjectId())
              .executeUpdate();
          return tagsOf(session, contactId);
        });
  }

  /**
   * Removes the link; removing a missing link is a no-op, also on an archived contact, so a tag can
   * always be taken off. Returns the contact's tags.
   */
  public List<Tag> untag(ClinicAccess access, UUID contactId, UUID tagId) {
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          requireContact(session, contactId);
          session
              .createNativeMutationQuery(
                  "DELETE FROM contact_tags WHERE contact_id = :contact AND tag_id = :tag")
              .setParameter("contact", contactId)
              .setParameter("tag", tagId)
              .executeUpdate();
          return tagsOf(session, contactId);
        });
  }

  public List<Tag> tagsOf(ClinicAccess access, UUID contactId) {
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          requireContact(session, contactId);
          return tagsOf(session, contactId);
        });
  }

  /** The contact's tags by name; also used by the contact detail, in its own transaction. */
  static List<Tag> tagsOf(Session session, UUID contactId) {
    return session
        .createSelectionQuery(
            """
            from TagEntity t
            where t.id in (select ct.tagId from ContactTagEntity ct where ct.contactId = :c)
            order by lower(t.name), t.id
            """,
            TagEntity.class)
        .setParameter("c", contactId)
        .getResultList()
        .stream()
        .map(TagEntity::toDomain)
        .toList();
  }

  private static TagEntity requireTag(Session session, UUID id) {
    TagEntity row = id == null ? null : session.find(TagEntity.class, id);
    if (row == null) throw new ContactException(ContactException.Reason.TAG_NOT_FOUND);
    return row;
  }

  private static ContactEntity requireContact(Session session, UUID id) {
    ContactEntity row = id == null ? null : session.find(ContactEntity.class, id);
    if (row == null) throw new ContactException(ContactException.Reason.NOT_FOUND);
    return row;
  }

  private static RuntimeException duplicate(ClinicDataException failure) {
    return failure.reason() == ClinicDataException.Reason.CONFLICT
        ? new ContactException(ContactException.Reason.DUPLICATE_TAG)
        : failure;
  }
}
