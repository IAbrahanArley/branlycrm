package br.com.branlycrm.contact.repositories;

import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.common.domain.PageResult;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.contact.domain.ConsentPolicy;
import br.com.branlycrm.contact.domain.ConsentRecord;
import br.com.branlycrm.contact.domain.Contact;
import br.com.branlycrm.contact.domain.ContactCreatedVia;
import br.com.branlycrm.contact.domain.ContactCreation;
import br.com.branlycrm.contact.domain.ContactDetail;
import br.com.branlycrm.contact.domain.ContactFields;
import br.com.branlycrm.contact.domain.ContactSearch;
import br.com.branlycrm.contact.domain.ContactSummary;
import br.com.branlycrm.contact.domain.PhoneNumber;
import br.com.branlycrm.contact.domain.SearchText;
import br.com.branlycrm.contact.domain.Tag;
import br.com.branlycrm.contact.exceptions.ContactException;
import br.com.branlycrm.contact.persistence.ContactEntity;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.exceptions.ClinicDataException;
import br.com.branlycrm.tenant.persistence.ClinicPersistence;
import br.com.branlycrm.tenant.persistence.ClinicTenant;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.hibernate.Session;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;

/**
 * Contacts of the clinic authorized by a {@link ClinicAccess}, in that clinic's own database. The
 * actor is always the access subject; permission checks belong to the calling service (CRM-021).
 */
@Repository
public class ContactRepository {
  private final ClinicPersistence persistence;
  private final Supplier<Instant> now;

  public ContactRepository(ClinicPersistence persistence, ObjectProvider<Clock> clock) {
    this.persistence = persistence;
    Clock source = clock.getIfAvailable(Clock::systemUTC);
    // PostgreSQL keeps microseconds: what is returned must equal what is stored.
    this.now = () -> source.instant().truncatedTo(ChronoUnit.MICROS);
  }

  /**
   * Creates the contact, or returns the one already holding the number. The conflict is resolved
   * inside the statement, so concurrent creations of one number never fail and never log it.
   */
  public ContactCreation create(ClinicAccess access, ContactFields fields, ContactCreatedVia via) {
    UUID id = UUID.randomUUID();
    Instant at = now.get();
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          // The holder of the number may change it between the insert and the lookup: try again.
          for (int attempt = 0; attempt < 2; attempt++) {
            if (insert(session, id, fields, via, at, access.subjectId())) {
              return new ContactCreation.Created(id);
            }
            Optional<UUID> existing = holderOf(session, fields, null);
            if (existing.isPresent()) return new ContactCreation.Existing(existing.get());
          }
          throw new ContactException(ContactException.Reason.STALE);
        });
  }

  /** The contact, or empty for a missing id and for another clinic's id alike. */
  public Optional<Contact> find(ClinicAccess access, UUID id) {
    if (id == null) return Optional.empty();
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session ->
            Optional.ofNullable(session.find(ContactEntity.class, id))
                .map(row -> stored(row, ContactEntity::toDomain)));
  }

  /**
   * Replaces the editable fields of the contact read at {@code expectedVersion}. A number already
   * held by another contact is refused before writing, with that contact's id; only a concurrent
   * change can still reach the unique constraint.
   *
   * @throws ContactException NOT_FOUND, STALE or DUPLICATE_PHONE
   */
  public Contact update(ClinicAccess access, UUID id, long expectedVersion, ContactFields fields) {
    Instant at = now.get();
    try {
      return persistence.inTransaction(
          ClinicTenant.of(access),
          session -> {
            ContactEntity row = require(session, id);
            if (row.archived()) throw new ContactException(ContactException.Reason.ARCHIVED);
            if (row.version() != expectedVersion) {
              throw new ContactException(ContactException.Reason.STALE);
            }
            Optional<UUID> holder = holderOf(session, fields, id);
            if (holder.isPresent()) {
              throw new ContactException(ContactException.Reason.DUPLICATE_PHONE, holder.get());
            }
            row.edit(fields, access.subjectId(), at);
            session.flush();
            return stored(row, ContactEntity::toDomain);
          });
    } catch (ClinicDataException failure) {
      throw writeConflict(failure, ContactException.Reason.DUPLICATE_PHONE);
    }
  }

  /** Archives the contact; it keeps its number. */
  public Contact archive(ClinicAccess access, UUID id) {
    return setArchived(access, id, true);
  }

  /**
   * Archives or unarchives the contact. Idempotent, also when two requests race: the first archive
   * date is kept and both see the same final state.
   */
  public Contact setArchived(ClinicAccess access, UUID id, boolean archived) {
    Instant at = now.get();
    try {
      return persistence.inTransaction(
          ClinicTenant.of(access),
          session -> {
            ContactEntity row = require(session, id);
            if (archived) row.archive(access.subjectId(), at);
            else row.unarchive(access.subjectId(), at);
            session.flush();
            return stored(row, ContactEntity::toDomain);
          });
    } catch (ClinicDataException failure) {
      if (failure.reason() != ClinicDataException.Reason.STALE) throw failure;
      Contact current =
          find(access, id)
              .orElseThrow(() -> new ContactException(ContactException.Reason.NOT_FOUND));
      if (current.archived() == archived) return current;
      throw new ContactException(ContactException.Reason.STALE);
    }
  }

  /**
   * The contact with its tags and current consent, read in one transaction so the three parts
   * agree. Empty for a missing id and for another clinic's id alike.
   */
  public Optional<ContactDetail> detail(ClinicAccess access, UUID id) {
    if (id == null) return Optional.empty();
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          ContactEntity row = session.find(ContactEntity.class, id);
          if (row == null) return Optional.<ContactDetail>empty();
          Contact contact = stored(row, ContactEntity::toDomain);
          List<Tag> tags = TagRepository.tagsOf(session, id);
          List<ConsentRecord> history = ConsentRepository.history(session, id);
          return Optional.of(new ContactDetail(contact, tags, ConsentPolicy.current(history)));
        });
  }

  /** The contact holding the number in this clinic, archived ones included. */
  public Optional<ContactSummary> findByPhone(ClinicAccess access, PhoneNumber phone) {
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session ->
            session
                .createSelectionQuery(
                    "from ContactEntity c where c.phoneE164 = :phone", ContactEntity.class)
                .setParameter("phone", phone.e164())
                .getResultList()
                .stream()
                .findFirst()
                .map(row -> stored(row, ContactEntity::toSummary)));
  }

  /**
   * One page of contacts ordered by name, plus the total matching the filters. Items carry no notes
   * nor birth date. The total and the page are read in two statements and may differ by a write
   * made between them.
   */
  public PageResult<ContactSummary> search(
      ClinicAccess access, ContactSearch search, PageQuery page) {
    Map<String, Object> parameters = new HashMap<>();
    String where = where(search, parameters);
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          var rows =
              session.createSelectionQuery(
                  "from ContactEntity c" + where + " order by c.nameSearch, c.id",
                  ContactEntity.class);
          var count =
              session.createSelectionQuery(
                  "select count(c) from ContactEntity c" + where, Long.class);
          parameters.forEach(rows::setParameter);
          parameters.forEach(count::setParameter);
          List<ContactSummary> items =
              rows
                  .setFirstResult(page.offset())
                  .setMaxResults(page.pageSize())
                  .getResultList()
                  .stream()
                  .map(row -> stored(row, ContactEntity::toSummary))
                  .toList();
          return new PageResult<>(items, count.getSingleResult());
        });
  }

  /** Only constant fragments are concatenated; every value is a bound parameter. */
  private static String where(ContactSearch search, Map<String, Object> parameters) {
    StringBuilder where = new StringBuilder(" where 1 = 1");
    if (!search.includeArchived()) where.append(" and c.archivedAt is null");
    if (search.tagId() != null) {
      where.append(
          " and exists (select 1 from ContactTagEntity t where t.contactId = c.id and t.tagId = :tag)");
      parameters.put("tag", search.tagId());
    }
    if (search.term() != null) {
      String escape = " escape '" + SearchText.ESCAPE + "'";
      where.append(" and (c.nameSearch like :name").append(escape);
      parameters.put("name", SearchText.contains(search.nameTerm()));
      where.append(" or lower(c.email) like :email").append(escape);
      parameters.put("email", SearchText.startsWith(search.emailTerm()));
      if (search.phoneDigits() != null) {
        where.append(" or c.phoneE164 like :phone").append(escape);
        parameters.put("phone", SearchText.contains(search.phoneDigits()));
      }
      if (search.exactPhone() != null) {
        where.append(" or c.phoneE164 = :exact");
        parameters.put("exact", search.exactPhone());
      }
      where.append(")");
    }
    return where.toString();
  }

  private static boolean insert(
      Session session,
      UUID id,
      ContactFields fields,
      ContactCreatedVia via,
      Instant at,
      UUID actor) {
    return !session
        .createNativeQuery(
            """
            INSERT INTO contacts (id, name, name_search, phone_e164, email, birth_date, notes,
                                  created_via, created_at, created_by, updated_at, updated_by)
            VALUES (:id, :name, :search, :phone, :email, :birth, :notes, :via,
                    :at, :actor, :at, :actor)
            ON CONFLICT (phone_e164) DO NOTHING
            RETURNING id
            """,
            UUID.class)
        .setParameter("id", id)
        .setParameter("name", fields.name())
        .setParameter("search", fields.nameSearch())
        .setParameter("phone", fields.phone().e164())
        .setParameter("email", fields.email(), String.class)
        .setParameter("birth", fields.birthDate(), LocalDate.class)
        .setParameter("notes", fields.notes(), String.class)
        .setParameter("via", via.name())
        .setParameter("at", at)
        .setParameter("actor", actor)
        .getResultList()
        .isEmpty();
  }

  /** The contact holding the number, other than {@code except}. */
  private static Optional<UUID> holderOf(Session session, ContactFields fields, UUID except) {
    return session
        .createNativeQuery(
            "SELECT id FROM contacts WHERE phone_e164 = :phone AND id IS DISTINCT FROM :except",
            UUID.class)
        .setParameter("phone", fields.phone().e164())
        .setParameter("except", except, UUID.class)
        .getResultList()
        .stream()
        .findFirst();
  }

  private static ContactEntity require(Session session, UUID id) {
    ContactEntity row = id == null ? null : session.find(ContactEntity.class, id);
    if (row == null) throw new ContactException(ContactException.Reason.NOT_FOUND);
    return row;
  }

  /**
   * A row the current rules refuse was written outside the application: it is invalid data, never
   * the client's input error.
   */
  private static <T> T stored(ContactEntity row, Function<ContactEntity, T> mapping) {
    try {
      return mapping.apply(row);
    } catch (InvalidInputException | IllegalArgumentException invalid) {
      throw new ClinicDataException(ClinicDataException.Reason.INVALID_DATA, invalid);
    }
  }

  private static RuntimeException writeConflict(
      ClinicDataException failure, ContactException.Reason uniqueViolation) {
    return switch (failure.reason()) {
      case CONFLICT -> new ContactException(uniqueViolation);
      case STALE -> new ContactException(ContactException.Reason.STALE);
      default -> failure;
    };
  }
}
