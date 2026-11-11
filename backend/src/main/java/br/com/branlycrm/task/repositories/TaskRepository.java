package br.com.branlycrm.task.repositories;

import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.common.domain.PageResult;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.task.domain.Task;
import br.com.branlycrm.task.domain.Task.TaskStatus;
import br.com.branlycrm.task.domain.TaskFields;
import br.com.branlycrm.task.domain.TaskSearch;
import br.com.branlycrm.task.exceptions.TaskException;
import br.com.branlycrm.task.exceptions.TaskException.Reason;
import br.com.branlycrm.task.persistence.TaskEntity;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.exceptions.ClinicDataException;
import br.com.branlycrm.tenant.persistence.ClinicPersistence;
import br.com.branlycrm.tenant.persistence.ClinicTenant;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.hibernate.Session;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;

/**
 * Tasks of the clinic authorized by a {@link ClinicAccess} (CRM-040). The contact and lead are
 * checked in the clinic database; the responsible's membership belongs to the calling service.
 */
@Repository
public class TaskRepository {
  private final ClinicPersistence persistence;
  private final Supplier<Instant> now;

  public TaskRepository(ClinicPersistence persistence, ObjectProvider<Clock> clock) {
    this.persistence = persistence;
    Clock source = clock.getIfAvailable(Clock::systemUTC);
    this.now = () -> source.instant().truncatedTo(ChronoUnit.MICROS);
  }

  /**
   * @throws TaskException CONTACT_UNAVAILABLE or LEAD_MISMATCH
   */
  public Task create(ClinicAccess access, TaskFields fields, UUID contactId, UUID leadId) {
    Instant at = now.get();
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          requireActiveContact(session, contactId);
          if (leadId != null) requireLeadOf(session, leadId, contactId);
          TaskEntity row = new TaskEntity(fields, contactId, leadId, access.subjectId(), at);
          session.persist(row);
          session.flush();
          return stored(row);
        });
  }

  /**
   * @throws TaskException NOT_FOUND
   */
  public Task get(ClinicAccess access, UUID id) {
    return persistence.inTransaction(
        ClinicTenant.of(access), session -> stored(require(session, id)));
  }

  /**
   * Replaces the editable fields of the open task read at {@code expectedVersion}.
   *
   * @throws TaskException NOT_FOUND, DONE or STALE
   */
  public Task edit(ClinicAccess access, UUID id, long expectedVersion, TaskFields fields) {
    return change(
        access,
        id,
        expectedVersion,
        TaskStatus.OPEN,
        (row, at) -> row.edit(fields, access.subjectId(), at));
  }

  /**
   * @throws TaskException NOT_FOUND, DONE or STALE
   */
  public Task complete(ClinicAccess access, UUID id, long expectedVersion, String note) {
    return change(
        access,
        id,
        expectedVersion,
        TaskStatus.OPEN,
        (row, at) -> row.complete(note, access.subjectId(), at));
  }

  /**
   * Reopens a completed task; an open one answers STALE (the screen is outdated).
   *
   * @throws TaskException NOT_FOUND or STALE
   */
  public Task reopen(ClinicAccess access, UUID id, long expectedVersion) {
    return change(
        access,
        id,
        expectedVersion,
        TaskStatus.DONE,
        (row, at) -> row.reopen(access.subjectId(), at));
  }

  /** Open tasks by due date (completed ones by completion, newest first), paged. */
  public PageResult<Task> search(ClinicAccess access, TaskSearch search, PageQuery page) {
    Instant at = now.get();
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          Map<String, Object> parameters = new HashMap<>();
          TaskStatus status = search.effectiveStatus();
          StringBuilder where = new StringBuilder(" where t.status = :status");
          parameters.put("status", status);
          if (search.responsibleId() != null) {
            where.append(" and t.responsibleId = :responsible");
            parameters.put("responsible", search.responsibleId());
          }
          if (search.contactId() != null) {
            where.append(" and t.contactId = :contact");
            parameters.put("contact", search.contactId());
          }
          if (search.leadId() != null) {
            where.append(" and t.leadId = :lead");
            parameters.put("lead", search.leadId());
          }
          if (search.due() != null) {
            Instant endOfToday = endOfToday(session, at);
            switch (search.due()) {
              case OVERDUE -> {
                where.append(" and t.dueAt < :now");
                parameters.put("now", at);
              }
              case TODAY -> {
                where.append(" and t.dueAt >= :now and t.dueAt < :end");
                parameters.put("now", at);
                parameters.put("end", endOfToday);
              }
              case UPCOMING -> {
                where.append(" and t.dueAt >= :end");
                parameters.put("end", endOfToday);
              }
            }
          }
          String order =
              status == TaskStatus.OPEN
                  ? " order by t.dueAt, t.id"
                  : " order by t.completedAt desc, t.id";
          var rows =
              session.createSelectionQuery("from TaskEntity t" + where + order, TaskEntity.class);
          var count =
              session.createSelectionQuery("select count(t) from TaskEntity t" + where, Long.class);
          parameters.forEach(
              (name, value) -> {
                rows.setParameter(name, value);
                count.setParameter(name, value);
              });
          List<Task> items =
              rows
                  .setFirstResult(page.offset())
                  .setMaxResults(page.pageSize())
                  .getResultList()
                  .stream()
                  .map(TaskRepository::stored)
                  .toList();
          return new PageResult<>(items, count.getSingleResult());
        });
  }

  @FunctionalInterface
  private interface Change {
    void apply(TaskEntity row, Instant at);
  }

  private Task change(
      ClinicAccess access, UUID id, long expectedVersion, TaskStatus required, Change change) {
    Instant at = now.get();
    try {
      return persistence.inTransaction(
          ClinicTenant.of(access),
          session -> {
            TaskEntity row = require(session, id);
            if (row.status() != required) {
              throw new TaskException(required == TaskStatus.OPEN ? Reason.DONE : Reason.STALE);
            }
            if (row.version() != expectedVersion) throw new TaskException(Reason.STALE);
            change.apply(row, at);
            session.flush();
            return stored(row);
          });
    } catch (ClinicDataException failure) {
      if (failure.reason() == ClinicDataException.Reason.STALE) {
        throw new TaskException(Reason.STALE);
      }
      throw failure;
    }
  }

  /** The end of today (next midnight) in the clinic's time zone. */
  private static Instant endOfToday(Session session, Instant at) {
    String zone =
        session
            .createNativeQuery("SELECT time_zone FROM clinic_settings", String.class)
            .getResultList()
            .stream()
            .findFirst()
            .orElseThrow(
                () -> new ClinicDataException(ClinicDataException.Reason.INVALID_DATA, null));
    try {
      ZoneId clinic = ZoneId.of(zone);
      return LocalDate.ofInstant(at, clinic).plusDays(1).atStartOfDay(clinic).toInstant();
    } catch (DateTimeException invalid) {
      throw new ClinicDataException(ClinicDataException.Reason.INVALID_DATA, invalid);
    }
  }

  private static void requireActiveContact(Session session, UUID contactId) {
    List<Boolean> archived =
        session
            .createNativeQuery(
                "SELECT archived_at IS NOT NULL FROM contacts WHERE id = :id FOR SHARE",
                Boolean.class)
            .setParameter("id", contactId)
            .getResultList();
    if (archived.isEmpty() || archived.getFirst()) {
      throw new TaskException(Reason.CONTACT_UNAVAILABLE);
    }
  }

  private static void requireLeadOf(Session session, UUID leadId, UUID contactId) {
    boolean matches =
        !session
            .createNativeQuery(
                "SELECT 1 FROM leads WHERE id = :lead AND contact_id = :contact", Integer.class)
            .setParameter("lead", leadId)
            .setParameter("contact", contactId)
            .getResultList()
            .isEmpty();
    if (!matches) throw new TaskException(Reason.LEAD_MISMATCH);
  }

  private static TaskEntity require(Session session, UUID id) {
    TaskEntity row = id == null ? null : session.find(TaskEntity.class, id);
    if (row == null) throw new TaskException(Reason.NOT_FOUND);
    return row;
  }

  private static Task stored(TaskEntity row) {
    return guarded(row, TaskEntity::toDomain);
  }

  private static <T> T guarded(TaskEntity row, Function<TaskEntity, T> mapping) {
    try {
      return mapping.apply(row);
    } catch (InvalidInputException | IllegalArgumentException invalid) {
      throw new ClinicDataException(ClinicDataException.Reason.INVALID_DATA, invalid);
    }
  }
}
