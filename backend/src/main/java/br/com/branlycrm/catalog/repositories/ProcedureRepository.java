package br.com.branlycrm.catalog.repositories;

import br.com.branlycrm.catalog.domain.Procedure;
import br.com.branlycrm.catalog.domain.ProcedureFields;
import br.com.branlycrm.catalog.exceptions.CatalogException;
import br.com.branlycrm.catalog.exceptions.CatalogException.Reason;
import br.com.branlycrm.catalog.persistence.ProcedureEntity;
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

/** Procedures of the clinic authorized by a {@link ClinicAccess} (CRM-034). */
@Repository
public class ProcedureRepository {
  /**
   * The catalog is returned whole, so it is kept small. Inactive ones count, since nothing is
   * deleted. Approximate: two concurrent creations can both pass the count.
   */
  public static final int MAX_PROCEDURES = 300;

  private final ClinicPersistence persistence;
  private final Supplier<Instant> now;

  public ProcedureRepository(ClinicPersistence persistence, ObjectProvider<Clock> clock) {
    this.persistence = persistence;
    this.now = CatalogRows.clock(clock);
  }

  /** Ordered by name; inactive ones only when asked. */
  public List<Procedure> list(ClinicAccess access, boolean includeInactive) {
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session ->
            session
                .createSelectionQuery(
                    "from ProcedureEntity p"
                        + (includeInactive ? "" : " where p.active = true")
                        + " order by lower(p.name), p.id",
                    ProcedureEntity.class)
                .getResultList()
                .stream()
                .map(row -> CatalogRows.stored(row, ProcedureEntity::toDomain))
                .toList());
  }

  /**
   * @throws CatalogException PROCEDURE_NOT_FOUND
   */
  public Procedure get(ClinicAccess access, UUID id) {
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> CatalogRows.stored(require(session, id), ProcedureEntity::toDomain));
  }

  /**
   * @throws CatalogException PROCEDURE_DUPLICATE or PROCEDURE_LIMIT
   */
  public Procedure create(ClinicAccess access, ProcedureFields fields) {
    Instant at = now.get();
    try {
      return persistence.inTransaction(
          ClinicTenant.of(access),
          session -> {
            long existing =
                session
                    .createSelectionQuery("select count(p) from ProcedureEntity p", Long.class)
                    .getSingleResult();
            if (existing >= MAX_PROCEDURES) throw new CatalogException(Reason.PROCEDURE_LIMIT);
            ProcedureEntity row = new ProcedureEntity(fields, access.subjectId(), at);
            session.persist(row);
            session.flush();
            return CatalogRows.stored(row, ProcedureEntity::toDomain);
          });
    } catch (ClinicDataException failure) {
      throw CatalogRows.conflict(failure, Reason.PROCEDURE_DUPLICATE, Reason.PROCEDURE_STALE);
    }
  }

  /**
   * Replaces the editable fields of the procedure read at {@code expectedVersion}. Inactive ones
   * can be edited too.
   *
   * @throws CatalogException PROCEDURE_NOT_FOUND, PROCEDURE_STALE or PROCEDURE_DUPLICATE
   */
  public Procedure update(
      ClinicAccess access, UUID id, long expectedVersion, ProcedureFields fields) {
    Instant at = now.get();
    try {
      return persistence.inTransaction(
          ClinicTenant.of(access),
          session -> {
            ProcedureEntity row = require(session, id);
            if (row.version() != expectedVersion) {
              throw new CatalogException(Reason.PROCEDURE_STALE);
            }
            row.edit(fields, access.subjectId(), at);
            session.flush();
            return CatalogRows.stored(row, ProcedureEntity::toDomain);
          });
    } catch (ClinicDataException failure) {
      throw CatalogRows.conflict(failure, Reason.PROCEDURE_DUPLICATE, Reason.PROCEDURE_STALE);
    }
  }

  /**
   * Activates or deactivates the procedure. Idempotent, also when two requests race: both see the
   * same final state.
   *
   * @throws CatalogException PROCEDURE_NOT_FOUND, or PROCEDURE_STALE when an edit raced it
   */
  public Procedure setActive(ClinicAccess access, UUID id, boolean active) {
    Instant at = now.get();
    try {
      return persistence.inTransaction(
          ClinicTenant.of(access),
          session -> {
            ProcedureEntity row = require(session, id);
            if (row.active() != active) {
              row.setActive(active, access.subjectId(), at);
              session.flush();
            }
            return CatalogRows.stored(row, ProcedureEntity::toDomain);
          });
    } catch (ClinicDataException failure) {
      if (failure.reason() != ClinicDataException.Reason.STALE) throw failure;
      Procedure current = get(access, id);
      if (current.active() == active) return current;
      throw new CatalogException(Reason.PROCEDURE_STALE);
    }
  }

  private static ProcedureEntity require(Session session, UUID id) {
    ProcedureEntity row = id == null ? null : session.find(ProcedureEntity.class, id);
    if (row == null) throw new CatalogException(Reason.PROCEDURE_NOT_FOUND);
    return row;
  }
}
