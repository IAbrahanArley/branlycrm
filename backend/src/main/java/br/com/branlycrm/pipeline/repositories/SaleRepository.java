package br.com.branlycrm.pipeline.repositories;

import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.common.domain.PageResult;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.pipeline.domain.Sale;
import br.com.branlycrm.pipeline.domain.SaleEvent;
import br.com.branlycrm.pipeline.domain.SaleEvent.SaleEventType;
import br.com.branlycrm.pipeline.domain.SaleFields;
import br.com.branlycrm.pipeline.domain.SaleSearch;
import br.com.branlycrm.pipeline.domain.SaleStatus;
import br.com.branlycrm.pipeline.exceptions.LeadException;
import br.com.branlycrm.pipeline.exceptions.SaleException;
import br.com.branlycrm.pipeline.persistence.LeadEntity;
import br.com.branlycrm.pipeline.persistence.SaleEntity;
import br.com.branlycrm.pipeline.persistence.SaleEventEntity;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.exceptions.ClinicDataException;
import br.com.branlycrm.tenant.persistence.ClinicPersistence;
import br.com.branlycrm.tenant.persistence.ClinicTenant;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.Session;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;

/**
 * Sales of the clinic authorized by a {@link ClinicAccess} (CRM-035). Registering one closes its
 * lead as won, writes both histories and marks the contact as a patient in one transaction. Locks
 * are taken in a fixed order: lead or sale, then the catalog items, then the contact.
 */
@Repository
public class SaleRepository {
  private final ClinicPersistence persistence;
  private final Clock clock;
  private final Supplier<Instant> now;

  public SaleRepository(ClinicPersistence persistence, ObjectProvider<Clock> clock) {
    this.persistence = persistence;
    this.clock = clock.getIfAvailable(Clock::systemUTC);
    this.now = () -> this.clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  /**
   * Registers the sale of the open lead read at {@code expectedVersion}, with active catalog items.
   *
   * @throws LeadException NOT_FOUND, CLOSED or STALE
   * @throws SaleException PROCEDURE_UNAVAILABLE or PROFESSIONAL_UNAVAILABLE
   */
  public Sale register(ClinicAccess access, UUID leadId, long expectedVersion, SaleFields fields) {
    Instant at = now.get();
    UUID actor = access.subjectId();
    try {
      return persistence.inTransaction(
          ClinicTenant.of(access),
          session -> {
            LeadEntity lead = LeadRepository.closeWon(session, leadId, expectedVersion, actor, at);
            requireActive(session, "procedures", fields.procedureId(), procedureUnavailable());
            if (fields.professionalId() != null) {
              requireActive(
                  session, "professionals", fields.professionalId(), professionalUnavailable());
            }
            SaleFields resolved = fields.on(day(session, fields.soldOn(), at));
            SaleEntity sale = new SaleEntity(lead.id(), lead.contactId(), resolved, actor, at);
            session.persist(sale);
            session.persist(
                new SaleEventEntity(
                    sale.id(), SaleEventType.CREATED, resolved, null, null, actor, at));
            session.flush();
            session
                .createNativeMutationQuery(
                    "UPDATE contacts SET patient_since = COALESCE(patient_since, :at)"
                        + " WHERE id = :contact")
                .setParameter("at", at)
                .setParameter("contact", lead.contactId())
                .executeUpdate();
            return stored(sale);
          });
    } catch (ClinicDataException failure) {
      // A concurrent registration of the same lead: either its version or the one-active-sale
      // index refused this one. That index is the only unique constraint this transaction can
      // reach; review this mapping before adding another one to sales or sale_events.
      if (failure.reason() == ClinicDataException.Reason.STALE
          || failure.reason() == ClinicDataException.Reason.CONFLICT) {
        throw new LeadException(LeadException.Reason.STALE);
      }
      throw failure;
    }
  }

  /**
   * Replaces the values of the active sale read at {@code expectedVersion}. The procedure and the
   * professional already on the sale may be inactive; a new choice must be active. Correcting to
   * the same values changes nothing and writes no event.
   *
   * @throws SaleException NOT_FOUND, CANCELLED, STALE, PROCEDURE_UNAVAILABLE or
   *     PROFESSIONAL_UNAVAILABLE
   */
  public Sale correct(
      ClinicAccess access, UUID id, long expectedVersion, SaleFields fields, String reason) {
    Objects.requireNonNull(fields.soldOn(), "soldOn");
    Instant at = now.get();
    UUID actor = access.subjectId();
    return write(
        access,
        session -> {
          SaleEntity sale = lockActive(session, id, expectedVersion);
          SaleFields previous = sale.fields();
          if (previous.equals(fields)) return stored(sale);
          if (!fields.procedureId().equals(previous.procedureId())) {
            requireActive(session, "procedures", fields.procedureId(), procedureUnavailable());
          }
          if (fields.professionalId() != null
              && !fields.professionalId().equals(previous.professionalId())) {
            requireActive(
                session, "professionals", fields.professionalId(), professionalUnavailable());
          }
          if (fields.soldOn().isAfter(today(session, at)))
            throw new InvalidInputException("soldOn");
          sale.correct(fields, actor, at);
          session.persist(
              new SaleEventEntity(
                  sale.id(), SaleEventType.CORRECTED, fields, previous, reason, actor, at));
          session.flush();
          return stored(sale);
        });
  }

  /**
   * Cancels the active sale read at {@code expectedVersion}. The lead stays won; the contact stops
   * being a patient when no active sale is left.
   *
   * @throws SaleException NOT_FOUND, CANCELLED or STALE
   */
  public Sale cancel(ClinicAccess access, UUID id, long expectedVersion, String reason) {
    Instant at = now.get();
    UUID actor = access.subjectId();
    return write(
        access,
        session -> {
          SaleEntity sale = lockActive(session, id, expectedVersion);
          sale.cancel(reason, actor, at);
          session.persist(
              new SaleEventEntity(
                  sale.id(), SaleEventType.CANCELLED, null, null, reason, actor, at));
          session.flush();
          // Lock the contact first, in its own statement: the UPDATE below then takes a snapshot
          // after any concurrent sale of this contact committed, so it sees that sale's status.
          session
              .createNativeQuery(
                  "SELECT 1 FROM contacts WHERE id = :contact FOR NO KEY UPDATE", Integer.class)
              .setParameter("contact", sale.contactId())
              .getResultList();
          session
              .createNativeMutationQuery(
                  "UPDATE contacts SET patient_since = NULL WHERE id = :contact AND NOT EXISTS"
                      + " (SELECT 1 FROM sales WHERE contact_id = :contact AND status = 'ACTIVE')")
              .setParameter("contact", sale.contactId())
              .executeUpdate();
          return stored(sale);
        });
  }

  /**
   * @throws SaleException NOT_FOUND
   */
  public Sale get(ClinicAccess access, UUID id) {
    return persistence.inTransaction(
        ClinicTenant.of(access), session -> stored(require(session, id)));
  }

  /** The sale's history, oldest first. */
  public List<SaleEvent> history(ClinicAccess access, UUID id) {
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          require(session, id);
          return session
              .createSelectionQuery(
                  "from SaleEventEntity e where e.saleId = :sale order by e.seq",
                  SaleEventEntity.class)
              .setParameter("sale", id)
              .getResultList()
              .stream()
              .map(SaleRepository::storedEvent)
              .toList();
        });
  }

  /** Newest sale day first. */
  public PageResult<Sale> search(ClinicAccess access, SaleSearch search, PageQuery page) {
    Map<String, Object> parameters = new HashMap<>();
    StringBuilder where = new StringBuilder(" where 1 = 1");
    if (search.contactId() != null) {
      where.append(" and s.contactId = :contact");
      parameters.put("contact", search.contactId());
    }
    if (search.leadId() != null) {
      where.append(" and s.leadId = :lead");
      parameters.put("lead", search.leadId());
    }
    if (search.status() != null) {
      where.append(" and s.status = :status");
      parameters.put("status", search.status());
    }
    if (search.from() != null) {
      where.append(" and s.soldOn >= :from");
      parameters.put("from", search.from());
    }
    if (search.to() != null) {
      where.append(" and s.soldOn <= :to");
      parameters.put("to", search.to());
    }
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          var rows =
              session.createSelectionQuery(
                  "from SaleEntity s" + where + " order by s.soldOn desc, s.createdAt desc, s.id",
                  SaleEntity.class);
          var count =
              session.createSelectionQuery("select count(s) from SaleEntity s" + where, Long.class);
          parameters.forEach(
              (name, value) -> {
                rows.setParameter(name, value);
                count.setParameter(name, value);
              });
          List<Sale> items =
              rows
                  .setFirstResult(page.offset())
                  .setMaxResults(page.pageSize())
                  .getResultList()
                  .stream()
                  .map(SaleRepository::stored)
                  .toList();
          return new PageResult<>(items, count.getSingleResult());
        });
  }

  @FunctionalInterface
  private interface Write {
    Sale apply(Session session);
  }

  private Sale write(ClinicAccess access, Write write) {
    try {
      return persistence.inTransaction(ClinicTenant.of(access), write::apply);
    } catch (ClinicDataException failure) {
      if (failure.reason() == ClinicDataException.Reason.STALE) {
        throw new SaleException(SaleException.Reason.STALE);
      }
      throw failure;
    }
  }

  private static SaleEntity lockActive(Session session, UUID id, long expectedVersion) {
    SaleEntity sale =
        id == null ? null : session.find(SaleEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
    if (sale == null) throw new SaleException(SaleException.Reason.NOT_FOUND);
    if (sale.status() == SaleStatus.CANCELLED) {
      throw new SaleException(SaleException.Reason.CANCELLED);
    }
    if (sale.version() != expectedVersion) throw new SaleException(SaleException.Reason.STALE);
    return sale;
  }

  private static SaleEntity require(Session session, UUID id) {
    SaleEntity sale = id == null ? null : session.find(SaleEntity.class, id);
    if (sale == null) throw new SaleException(SaleException.Reason.NOT_FOUND);
    return sale;
  }

  /** Active catalog item, share-locked so it cannot be deactivated while the sale is written. */
  private static void requireActive(
      Session session, String table, UUID id, Supplier<SaleException> unavailable) {
    // The table name is one of two constants of this class, never client input.
    List<Boolean> active =
        session
            .createNativeQuery(
                "SELECT active FROM " + table + " WHERE id = :id FOR SHARE", Boolean.class)
            .setParameter("id", id)
            .getResultList();
    if (active.isEmpty() || !active.getFirst()) throw unavailable.get();
  }

  private static Supplier<SaleException> procedureUnavailable() {
    return () -> new SaleException(SaleException.Reason.PROCEDURE_UNAVAILABLE);
  }

  private static Supplier<SaleException> professionalUnavailable() {
    return () -> new SaleException(SaleException.Reason.PROFESSIONAL_UNAVAILABLE);
  }

  /** The requested day, or today, in the clinic's time zone; never after today. */
  private static LocalDate day(Session session, LocalDate requested, Instant at) {
    LocalDate today = today(session, at);
    if (requested == null) return today;
    if (requested.isAfter(today)) throw new InvalidInputException("soldOn");
    return requested;
  }

  private static LocalDate today(Session session, Instant at) {
    String zone =
        session
            .createNativeQuery("SELECT time_zone FROM clinic_settings", String.class)
            .getResultList()
            .stream()
            .findFirst()
            .orElseThrow(
                () -> new ClinicDataException(ClinicDataException.Reason.INVALID_DATA, null));
    try {
      return LocalDate.ofInstant(at, ZoneId.of(zone));
    } catch (DateTimeException invalid) {
      throw new ClinicDataException(ClinicDataException.Reason.INVALID_DATA, invalid);
    }
  }

  private static Sale stored(SaleEntity row) {
    try {
      return row.toDomain();
    } catch (InvalidInputException | IllegalArgumentException invalid) {
      throw new ClinicDataException(ClinicDataException.Reason.INVALID_DATA, invalid);
    }
  }

  private static SaleEvent storedEvent(SaleEventEntity row) {
    try {
      return row.toDomain();
    } catch (InvalidInputException | IllegalArgumentException invalid) {
      throw new ClinicDataException(ClinicDataException.Reason.INVALID_DATA, invalid);
    }
  }
}
