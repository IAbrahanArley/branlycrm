package br.com.branlycrm.pipeline.repositories;

import br.com.branlycrm.pipeline.domain.ArchivedStage;
import br.com.branlycrm.pipeline.exceptions.PipelineException;
import br.com.branlycrm.pipeline.exceptions.PipelineException.Reason;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.exceptions.ClinicDataException;
import br.com.branlycrm.tenant.persistence.ClinicPersistence;
import br.com.branlycrm.tenant.persistence.ClinicTenant;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.Session;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;

/**
 * Configuration writes of the funnel and loss reasons (CRM-036, ADR-033), as native statements
 * within the column grants of V11. Every stage write locks the funnel row, compares its version and
 * bumps it, so stage changes are serialized and a stale screen gets 409. Locks are taken in a fixed
 * order: funnel, then stage, then leads (read only).
 */
@Repository
public class PipelineConfigRepository {
  public static final int MAX_OPEN_STAGES = 12;
  public static final int MAX_LOSS_REASONS = 50;

  private final ClinicPersistence persistence;
  private final Supplier<Instant> now;

  public PipelineConfigRepository(ClinicPersistence persistence, ObjectProvider<Clock> clock) {
    this.persistence = persistence;
    Clock source = clock.getIfAvailable(Clock::systemUTC);
    this.now = () -> source.instant().truncatedTo(ChronoUnit.MICROS);
  }

  /** Archived open stages by name. */
  public List<ArchivedStage> archivedStages(ClinicAccess access) {
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session ->
            session
                .createNativeQuery(
                    "SELECT id, name FROM pipeline_stages WHERE archived_at IS NOT NULL"
                        + " ORDER BY lower(name), id",
                    Object[].class)
                .getResultList()
                .stream()
                .map(row -> new ArchivedStage((UUID) row[0], (String) row[1]))
                .toList());
  }

  /**
   * Adds an open stage at the end.
   *
   * @throws PipelineException PIPELINE_STALE, STAGE_LIMIT or STAGE_DUPLICATE
   */
  public void createStage(ClinicAccess access, long expectedVersion, String name) {
    stageWrite(
        access,
        expectedVersion,
        (session, funnel, actor, at) -> {
          if (openStages(session, funnel) >= MAX_OPEN_STAGES) {
            throw new PipelineException(Reason.STAGE_LIMIT);
          }
          session
              .createNativeMutationQuery(
                  """
                  INSERT INTO pipeline_stages (id, pipeline_id, name, kind, position, created_at,
                                               created_by, updated_at, updated_by)
                  VALUES (:id, :funnel, :name, 'OPEN', :position, :at, :actor, :at, :actor)
                  """)
              .setParameter("id", UUID.randomUUID())
              .setParameter("funnel", funnel)
              .setParameter("name", name)
              .setParameter("position", lastPosition(session, funnel) + 1)
              .setParameter("at", at)
              .setParameter("actor", actor)
              .executeUpdate();
          return true;
        });
  }

  /**
   * Renames an active stage, open or not.
   *
   * @throws PipelineException PIPELINE_STALE, STAGE_NOT_FOUND or STAGE_DUPLICATE
   */
  public void renameStage(ClinicAccess access, long expectedVersion, UUID stageId, String name) {
    stageWrite(
        access,
        expectedVersion,
        (session, funnel, actor, at) -> {
          int updated =
              session
                  .createNativeMutationQuery(
                      """
                      UPDATE pipeline_stages
                      SET name = :name, updated_at = :at, updated_by = :actor,
                          version = version + 1
                      WHERE id = :id AND pipeline_id = :funnel AND archived_at IS NULL
                      """)
                  .setParameter("name", name)
                  .setParameter("at", at)
                  .setParameter("actor", actor)
                  .setParameter("id", stageId)
                  .setParameter("funnel", funnel)
                  .executeUpdate();
          if (updated == 0) throw new PipelineException(Reason.STAGE_NOT_FOUND);
          return true;
        });
  }

  /**
   * Rewrites the positions of the active open stages in the order given, in one statement; the
   * unique position constraint is checked at its end.
   *
   * @throws PipelineException PIPELINE_STALE or STAGE_ORDER_MISMATCH
   */
  public void reorder(ClinicAccess access, long expectedVersion, List<UUID> order) {
    stageWrite(
        access,
        expectedVersion,
        (session, funnel, actor, at) -> {
          List<UUID> open =
              session
                  .createNativeQuery(
                      "SELECT id FROM pipeline_stages WHERE pipeline_id = :funnel"
                          + " AND kind = 'OPEN' AND archived_at IS NULL",
                      UUID.class)
                  .setParameter("funnel", funnel)
                  .getResultList();
          Set<UUID> given = new HashSet<>(order);
          if (given.size() != order.size() || !given.equals(new HashSet<>(open))) {
            throw new PipelineException(Reason.STAGE_ORDER_MISMATCH);
          }
          StringBuilder values = new StringBuilder();
          for (int i = 0; i < order.size(); i++) {
            if (i > 0) values.append(", ");
            // Parameter names are generated here, never taken from the client.
            values.append("(CAST(:s").append(i).append(" AS uuid), ").append(i + 1).append(")");
          }
          var update =
              session.createNativeMutationQuery(
                  "UPDATE pipeline_stages s SET position = o.position, updated_at = :at,"
                      + " updated_by = :actor, version = s.version + 1"
                      + " FROM (VALUES "
                      + values
                      + ") AS o (id, position)"
                      + " WHERE s.id = o.id AND s.pipeline_id = :funnel"
                      + " AND s.position IS DISTINCT FROM o.position");
          for (int i = 0; i < order.size(); i++) update.setParameter("s" + i, order.get(i));
          update
              .setParameter("at", at)
              .setParameter("actor", actor)
              .setParameter("funnel", funnel)
              .executeUpdate();
          return true;
        });
  }

  /**
   * Archives an open stage without open leads and renumbers the others, or unarchives it at the
   * end. Archiving twice, or unarchiving an active stage, changes nothing, not even the funnel
   * version.
   *
   * @throws PipelineException PIPELINE_STALE, STAGE_NOT_FOUND, STAGE_PROTECTED,
   *     STAGE_HAS_OPEN_LEADS, STAGE_LIMIT or STAGE_DUPLICATE
   */
  public void setStageArchived(
      ClinicAccess access, long expectedVersion, UUID stageId, boolean archived) {
    stageWrite(
        access,
        expectedVersion,
        (session, funnel, actor, at) -> {
          Object[] stage =
              session
                  .createNativeQuery(
                      "SELECT kind, system_key, archived_at IS NOT NULL FROM pipeline_stages"
                          + " WHERE id = :id AND pipeline_id = :funnel FOR UPDATE",
                      Object[].class)
                  .setParameter("id", stageId)
                  .setParameter("funnel", funnel)
                  .getResultList()
                  .stream()
                  .findFirst()
                  .orElseThrow(() -> new PipelineException(Reason.STAGE_NOT_FOUND));
          if (!"OPEN".equals(stage[0]) || "NEW".equals(stage[1])) {
            throw new PipelineException(Reason.STAGE_PROTECTED);
          }
          boolean isArchived = (Boolean) stage[2];
          if (isArchived == archived) return false;
          if (archived) {
            // The stage is locked: a concurrent move either committed before (seen here) or
            // waits and then sees the stage archived (LeadRepository share-locks it).
            boolean busy =
                !session
                    .createNativeQuery(
                        "SELECT 1 FROM leads WHERE stage_id = :id AND status = 'OPEN' LIMIT 1",
                        Integer.class)
                    .setParameter("id", stageId)
                    .getResultList()
                    .isEmpty();
            if (busy) throw new PipelineException(Reason.STAGE_HAS_OPEN_LEADS);
            session
                .createNativeMutationQuery(
                    """
                    UPDATE pipeline_stages
                    SET archived_at = :at, archived_by = :actor, position = NULL,
                        updated_at = :at, updated_by = :actor, version = version + 1
                    WHERE id = :id
                    """)
                .setParameter("at", at)
                .setParameter("actor", actor)
                .setParameter("id", stageId)
                .executeUpdate();
            session
                .createNativeMutationQuery(
                    """
                    UPDATE pipeline_stages s
                    SET position = r.position, updated_at = :at, updated_by = :actor,
                        version = s.version + 1
                    FROM (SELECT id, row_number() OVER (ORDER BY position) AS position
                          FROM pipeline_stages
                          WHERE pipeline_id = :funnel AND kind = 'OPEN'
                            AND archived_at IS NULL) r
                    WHERE s.id = r.id AND s.position <> r.position
                    """)
                .setParameter("at", at)
                .setParameter("actor", actor)
                .setParameter("funnel", funnel)
                .executeUpdate();
          } else {
            int open = openStages(session, funnel);
            if (open >= MAX_OPEN_STAGES) throw new PipelineException(Reason.STAGE_LIMIT);
            session
                .createNativeMutationQuery(
                    """
                    UPDATE pipeline_stages
                    SET archived_at = NULL, archived_by = NULL, position = :position,
                        updated_at = :at, updated_by = :actor, version = version + 1
                    WHERE id = :id
                    """)
                .setParameter("position", lastPosition(session, funnel) + 1)
                .setParameter("at", at)
                .setParameter("actor", actor)
                .setParameter("id", stageId)
                .executeUpdate();
          }
          return true;
        });
  }

  /**
   * @throws PipelineException LOSS_REASON_LIMIT or LOSS_REASON_DUPLICATE
   */
  public UUID createLossReason(ClinicAccess access, String name) {
    UUID id = UUID.randomUUID();
    Instant at = now.get();
    reasonWrite(
        access,
        session -> {
          long existing = lockReasons(session, false).size();
          if (existing >= MAX_LOSS_REASONS) throw new PipelineException(Reason.LOSS_REASON_LIMIT);
          session
              .createNativeMutationQuery(
                  """
                  INSERT INTO loss_reasons (id, name, created_at, created_by, updated_at,
                                            updated_by)
                  VALUES (:id, :name, :at, :actor, :at, :actor)
                  """)
              .setParameter("id", id)
              .setParameter("name", name)
              .setParameter("at", at)
              .setParameter("actor", access.subjectId())
              .executeUpdate();
        });
    return id;
  }

  /**
   * Renames the reason read at {@code expectedVersion}, archived or not.
   *
   * @throws PipelineException LOSS_REASON_NOT_FOUND, LOSS_REASON_STALE or LOSS_REASON_DUPLICATE
   */
  public void renameLossReason(ClinicAccess access, UUID id, long expectedVersion, String name) {
    Instant at = now.get();
    reasonWrite(
        access,
        session -> {
          Long version = reasonVersion(session, id);
          if (version != expectedVersion) throw new PipelineException(Reason.LOSS_REASON_STALE);
          // Compared again in the statement: a concurrent rename at the same version loses.
          int updated =
              session
                  .createNativeMutationQuery(
                      """
                      UPDATE loss_reasons
                      SET name = :name, updated_at = :at, updated_by = :actor,
                          version = version + 1
                      WHERE id = :id AND version = :expected
                      """)
                  .setParameter("name", name)
                  .setParameter("at", at)
                  .setParameter("actor", access.subjectId())
                  .setParameter("id", id)
                  .setParameter("expected", expectedVersion)
                  .executeUpdate();
          if (updated == 0) throw new PipelineException(Reason.LOSS_REASON_STALE);
        });
  }

  /**
   * Archives or unarchives the reason; idempotent. The active reasons are locked before counting,
   * so two concurrent archives never leave none.
   *
   * @throws PipelineException LOSS_REASON_NOT_FOUND or LOSS_REASON_LAST_ACTIVE
   */
  public void setLossReasonArchived(ClinicAccess access, UUID id, boolean archived) {
    Instant at = now.get();
    UUID actor = access.subjectId();
    reasonWrite(
        access,
        session -> {
          List<UUID> active = lockReasons(session, true);
          reasonVersion(session, id);
          boolean isArchived = !active.contains(id);
          if (isArchived == archived) return;
          if (archived && active.size() <= 1) {
            throw new PipelineException(Reason.LOSS_REASON_LAST_ACTIVE);
          }
          session
              .createNativeMutationQuery(
                  archived
                      ? "UPDATE loss_reasons SET archived_at = :at, archived_by = :actor,"
                          + " updated_at = :at, updated_by = :actor, version = version + 1"
                          + " WHERE id = :id"
                      : "UPDATE loss_reasons SET archived_at = NULL, archived_by = NULL,"
                          + " updated_at = :at, updated_by = :actor, version = version + 1"
                          + " WHERE id = :id")
              .setParameter("at", at)
              .setParameter("actor", actor)
              .setParameter("id", id)
              .executeUpdate();
        });
  }

  @FunctionalInterface
  private interface StageChange {
    /** Returns whether the funnel changed; nothing changed keeps its version. */
    boolean apply(Session session, UUID funnel, UUID actor, Instant at);
  }

  /** Locks the funnel, checks its version, applies the change and bumps the version. */
  private void stageWrite(ClinicAccess access, long expectedVersion, StageChange change) {
    Instant at = now.get();
    UUID actor = access.subjectId();
    try {
      persistence.inTransaction(
          ClinicTenant.of(access),
          session -> {
            Object[] funnel =
                session
                    .createNativeQuery(
                        "SELECT id, version FROM pipelines FOR UPDATE", Object[].class)
                    .getResultList()
                    .stream()
                    .findFirst()
                    .orElseThrow(
                        () ->
                            new ClinicDataException(ClinicDataException.Reason.INVALID_DATA, null));
            if (((Number) funnel[1]).longValue() != expectedVersion) {
              throw new PipelineException(Reason.PIPELINE_STALE);
            }
            UUID id = (UUID) funnel[0];
            if (!change.apply(session, id, actor, at)) return null;
            session
                .createNativeMutationQuery(
                    "UPDATE pipelines SET version = version + 1, updated_at = :at,"
                        + " updated_by = :actor WHERE id = :id")
                .setParameter("at", at)
                .setParameter("actor", actor)
                .setParameter("id", id)
                .executeUpdate();
            return null;
          });
    } catch (ClinicDataException failure) {
      // The only unique constraint a stage write can reach is the active name.
      if (failure.reason() == ClinicDataException.Reason.CONFLICT) {
        throw new PipelineException(Reason.STAGE_DUPLICATE);
      }
      throw failure;
    }
  }

  @FunctionalInterface
  private interface ReasonChange {
    void apply(Session session);
  }

  private void reasonWrite(ClinicAccess access, ReasonChange change) {
    try {
      persistence.inTransaction(
          ClinicTenant.of(access),
          session -> {
            change.apply(session);
            return null;
          });
    } catch (ClinicDataException failure) {
      if (failure.reason() == ClinicDataException.Reason.CONFLICT) {
        throw new PipelineException(Reason.LOSS_REASON_DUPLICATE);
      }
      throw failure;
    }
  }

  /**
   * Locks the loss reasons (only the active ones, or all) in id order, without a key lock, so
   * losses referencing them are not blocked and concurrent writers queue in the same order.
   */
  private static List<UUID> lockReasons(Session session, boolean activeOnly) {
    return session
        .createNativeQuery(
            "SELECT id FROM loss_reasons"
                + (activeOnly ? " WHERE archived_at IS NULL" : "")
                + " ORDER BY id FOR NO KEY UPDATE",
            UUID.class)
        .getResultList();
  }

  private static int lastPosition(Session session, UUID funnel) {
    return session
        .createNativeQuery(
            "SELECT COALESCE(max(position), 0) FROM pipeline_stages WHERE pipeline_id = :funnel",
            Integer.class)
        .setParameter("funnel", funnel)
        .getSingleResult();
  }

  private static int openStages(Session session, UUID funnel) {
    return session
        .createNativeQuery(
            "SELECT count(*) FROM pipeline_stages WHERE pipeline_id = :funnel"
                + " AND kind = 'OPEN' AND archived_at IS NULL",
            Long.class)
        .setParameter("funnel", funnel)
        .getSingleResult()
        .intValue();
  }

  private static Long reasonVersion(Session session, UUID id) {
    return session
        .createNativeQuery("SELECT version FROM loss_reasons WHERE id = :id", Long.class)
        .setParameter("id", id)
        .getResultList()
        .stream()
        .findFirst()
        .orElseThrow(() -> new PipelineException(Reason.LOSS_REASON_NOT_FOUND));
  }
}
