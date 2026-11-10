package br.com.branlycrm.catalog.repositories;

import br.com.branlycrm.catalog.domain.Professional;
import br.com.branlycrm.catalog.exceptions.CatalogException;
import br.com.branlycrm.catalog.exceptions.CatalogException.Reason;
import br.com.branlycrm.catalog.persistence.ProfessionalEntity;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.exceptions.ClinicDataException;
import br.com.branlycrm.tenant.persistence.ClinicPersistence;
import br.com.branlycrm.tenant.persistence.ClinicTenant;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.Session;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;

/** Professionals of the clinic authorized by a {@link ClinicAccess} (CRM-034). */
@Repository
public class ProfessionalRepository {
  /**
   * The catalog is returned whole, so it is kept small. Inactive ones count, since nothing is
   * deleted. Approximate: two concurrent creations can both pass the count.
   */
  public static final int MAX_PROFESSIONALS = 100;

  private final ClinicPersistence persistence;
  private final Supplier<Instant> now;

  public ProfessionalRepository(ClinicPersistence persistence, ObjectProvider<Clock> clock) {
    this.persistence = persistence;
    this.now = CatalogRows.clock(clock);
  }

  /** Ordered by name; inactive ones only when asked. */
  public List<Professional> list(ClinicAccess access, boolean includeInactive) {
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session ->
            session
                .createSelectionQuery(
                    "from ProfessionalEntity p"
                        + (includeInactive ? "" : " where p.active = true")
                        + " order by lower(p.name), p.id",
                    ProfessionalEntity.class)
                .getResultList()
                .stream()
                .map(row -> CatalogRows.stored(row, ProfessionalEntity::toDomain))
                .toList());
  }

  /**
   * @throws CatalogException PROFESSIONAL_NOT_FOUND
   */
  public Professional get(ClinicAccess access, UUID id) {
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> CatalogRows.stored(require(session, id), ProfessionalEntity::toDomain));
  }

  /**
   * @throws CatalogException PROFESSIONAL_DUPLICATE or PROFESSIONAL_LIMIT
   */
  public Professional create(ClinicAccess access, String name) {
    Instant at = now.get();
    try {
      return persistence.inTransaction(
          ClinicTenant.of(access),
          session -> {
            long existing =
                session
                    .createSelectionQuery("select count(p) from ProfessionalEntity p", Long.class)
                    .getSingleResult();
            if (existing >= MAX_PROFESSIONALS) {
              throw new CatalogException(Reason.PROFESSIONAL_LIMIT);
            }
            ProfessionalEntity row = new ProfessionalEntity(name, access.subjectId(), at);
            session.persist(row);
            session.flush();
            return CatalogRows.stored(row, ProfessionalEntity::toDomain);
          });
    } catch (ClinicDataException failure) {
      throw CatalogRows.conflict(failure, Reason.PROFESSIONAL_DUPLICATE, Reason.PROFESSIONAL_STALE);
    }
  }

  /**
   * Renames the professional read at {@code expectedVersion}.
   *
   * @throws CatalogException PROFESSIONAL_NOT_FOUND, PROFESSIONAL_STALE or PROFESSIONAL_DUPLICATE
   */
  public Professional rename(ClinicAccess access, UUID id, long expectedVersion, String name) {
    Instant at = now.get();
    try {
      return persistence.inTransaction(
          ClinicTenant.of(access),
          session -> {
            ProfessionalEntity row = require(session, id);
            if (row.version() != expectedVersion) {
              throw new CatalogException(Reason.PROFESSIONAL_STALE);
            }
            row.rename(name, access.subjectId(), at);
            session.flush();
            return CatalogRows.stored(row, ProfessionalEntity::toDomain);
          });
    } catch (ClinicDataException failure) {
      throw CatalogRows.conflict(failure, Reason.PROFESSIONAL_DUPLICATE, Reason.PROFESSIONAL_STALE);
    }
  }

  /**
   * Activates or deactivates the professional; idempotent, as for procedures.
   *
   * @throws CatalogException PROFESSIONAL_NOT_FOUND, or PROFESSIONAL_STALE when a rename raced it
   */
  public Professional setActive(ClinicAccess access, UUID id, boolean active) {
    Instant at = now.get();
    try {
      return persistence.inTransaction(
          ClinicTenant.of(access),
          session -> {
            ProfessionalEntity row = require(session, id);
            if (row.active() != active) {
              row.setActive(active, access.subjectId(), at);
              session.flush();
            }
            return CatalogRows.stored(row, ProfessionalEntity::toDomain);
          });
    } catch (ClinicDataException failure) {
      if (failure.reason() != ClinicDataException.Reason.STALE) throw failure;
      Professional current = get(access, id);
      if (current.active() == active) return current;
      throw new CatalogException(Reason.PROFESSIONAL_STALE);
    }
  }

  private static ProfessionalEntity require(Session session, UUID id) {
    ProfessionalEntity row = id == null ? null : session.find(ProfessionalEntity.class, id);
    if (row == null) throw new CatalogException(Reason.PROFESSIONAL_NOT_FOUND);
    return row;
  }
}
