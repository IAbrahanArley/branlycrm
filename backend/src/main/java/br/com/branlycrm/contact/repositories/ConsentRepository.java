package br.com.branlycrm.contact.repositories;

import br.com.branlycrm.contact.domain.ConsentChannel;
import br.com.branlycrm.contact.domain.ConsentPolicy;
import br.com.branlycrm.contact.domain.ConsentRecord;
import br.com.branlycrm.contact.domain.ConsentSource;
import br.com.branlycrm.contact.domain.ConsentState;
import br.com.branlycrm.contact.domain.ConsentStatus;
import br.com.branlycrm.contact.exceptions.ContactException;
import br.com.branlycrm.contact.persistence.ContactConsentEntity;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.persistence.ClinicPersistence;
import br.com.branlycrm.tenant.persistence.ClinicTenant;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.Session;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;

/**
 * Append-only consent history of a contact (PAC-08). The current state is derived from the history
 * by {@link ConsentPolicy}; the record time comes from the server clock, never the client.
 */
@Repository
public class ConsentRepository {
  private final ClinicPersistence persistence;
  private final Supplier<Instant> now;

  public ConsentRepository(ClinicPersistence persistence, ObjectProvider<Clock> clock) {
    this.persistence = persistence;
    Clock source = clock.getIfAvailable(Clock::systemUTC);
    // PostgreSQL keeps microseconds: what is returned must equal what is stored.
    this.now = () -> source.instant().truncatedTo(ChronoUnit.MICROS);
  }

  /**
   * Adds one record. The contact row is locked first, so two concurrent changes are checked against
   * the rules one after the other.
   *
   * @throws ContactException NOT_FOUND or CONSENT_REFUSED
   */
  public ConsentRecord record(
      ClinicAccess access,
      UUID contactId,
      ConsentChannel channel,
      ConsentStatus status,
      ConsentSource source) {
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          lockContact(session, contactId);
          List<ConsentRecord> history = history(session, contactId);
          ConsentState current = ConsentPolicy.current(history).get(channel);
          if (!ConsentPolicy.accepts(status, source, current)) {
            throw new ContactException(ContactException.Reason.CONSENT_REFUSED);
          }
          var row =
              new ContactConsentEntity(
                  contactId, channel, status, source, after(history), access.subjectId());
          session.persist(row);
          session.flush();
          return row.toDomain();
        });
  }

  /** Current state per channel; NONE where nothing was ever recorded. */
  public Map<ConsentChannel, ConsentState> current(ClinicAccess access, UUID contactId) {
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          requireContact(session, contactId);
          return ConsentPolicy.current(history(session, contactId));
        });
  }

  /** Full history, current record first on each channel. */
  public List<ConsentRecord> history(ClinicAccess access, UUID contactId) {
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          requireContact(session, contactId);
          return history(session, contactId).stream().sorted(ConsentPolicy.PRECEDENCE).toList();
        });
  }

  /**
   * The record time: the server clock, but always after every record of the contact. Writes are
   * serialized by the contact lock, so the history order is the order of writes even with clocks
   * that drift between processes or move back.
   */
  private Instant after(List<ConsentRecord> history) {
    Instant at = now.get();
    for (ConsentRecord record : history) {
      if (!record.recordedAt().isBefore(at)) at = record.recordedAt().plus(1, ChronoUnit.MICROS);
    }
    return at;
  }

  /** Unordered history; also used by the contact detail, in its own transaction. */
  static List<ConsentRecord> history(Session session, UUID contactId) {
    return session
        .createSelectionQuery(
            "from ContactConsentEntity c where c.contactId = :contact", ContactConsentEntity.class)
        .setParameter("contact", contactId)
        .getResultList()
        .stream()
        .map(ContactConsentEntity::toDomain)
        .toList();
  }

  private static void lockContact(Session session, UUID contactId) {
    List<UUID> locked =
        contactId == null
            ? List.of()
            : session
                .createNativeQuery(
                    "SELECT id FROM contacts WHERE id = :id FOR NO KEY UPDATE", UUID.class)
                .setParameter("id", contactId)
                .getResultList();
    if (locked.isEmpty()) throw new ContactException(ContactException.Reason.NOT_FOUND);
  }

  private static void requireContact(Session session, UUID contactId) {
    List<UUID> found =
        contactId == null
            ? List.of()
            : session
                .createNativeQuery("SELECT id FROM contacts WHERE id = :id", UUID.class)
                .setParameter("id", contactId)
                .getResultList();
    if (found.isEmpty()) throw new ContactException(ContactException.Reason.NOT_FOUND);
  }
}
