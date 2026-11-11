package br.com.branlycrm.pipeline.repositories;

import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.common.domain.PageResult;
import br.com.branlycrm.pipeline.domain.Lead;
import br.com.branlycrm.pipeline.domain.LeadEvent;
import br.com.branlycrm.pipeline.domain.LeadEventType;
import br.com.branlycrm.pipeline.domain.LeadNote;
import br.com.branlycrm.pipeline.domain.LeadOrigin;
import br.com.branlycrm.pipeline.domain.LeadSearch;
import br.com.branlycrm.pipeline.domain.LeadStatus;
import br.com.branlycrm.pipeline.domain.LeadSummary;
import br.com.branlycrm.pipeline.domain.OriginTouch;
import br.com.branlycrm.pipeline.domain.Temperature;
import br.com.branlycrm.pipeline.exceptions.LeadException;
import br.com.branlycrm.pipeline.persistence.LeadEntity;
import br.com.branlycrm.pipeline.persistence.LeadEventEntity;
import br.com.branlycrm.pipeline.persistence.LeadNoteEntity;
import br.com.branlycrm.pipeline.persistence.OriginTouchEntity;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.exceptions.ClinicDataException;
import br.com.branlycrm.tenant.persistence.ClinicPersistence;
import br.com.branlycrm.tenant.persistence.ClinicTenant;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.Session;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;

/**
 * Leads of the clinic authorized by a {@link ClinicAccess} (CRM-031). Every change writes its
 * timeline event in the same transaction. The actor is always the access subject; permissions and
 * the responsible's membership belong to the calling service.
 */
@Repository
public class LeadRepository {
  private final ClinicPersistence persistence;
  private final Supplier<Instant> now;

  public LeadRepository(ClinicPersistence persistence, ObjectProvider<Clock> clock) {
    this.persistence = persistence;
    Clock source = clock.getIfAvailable(Clock::systemUTC);
    this.now = () -> source.instant().truncatedTo(ChronoUnit.MICROS);
  }

  /**
   * Opens a lead for the contact in the entry stage (key NEW). A contact has at most one open lead:
   * the conflict is resolved inside the statement, so concurrent openings never create two.
   *
   * @throws LeadException CONTACT_UNAVAILABLE or ALREADY_OPEN (with the open lead's id)
   */
  public Lead open(
      ClinicAccess access, UUID contactId, Temperature temperature, UUID responsibleId) {
    return open(access, contactId, temperature, responsibleId, LeadOrigin.unidentified());
  }

  /** As {@link #open(ClinicAccess, UUID, Temperature, UUID)}, with the lead's first origin. */
  public Lead open(
      ClinicAccess access,
      UUID contactId,
      Temperature temperature,
      UUID responsibleId,
      LeadOrigin origin) {
    Objects.requireNonNull(origin, "origin");
    UUID id = UUID.randomUUID();
    Instant at = now.get();
    UUID actor = access.subjectId();
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          requireActiveContact(session, contactId);
          Object[] entry =
              session
                  .createNativeQuery(
                      "SELECT id, pipeline_id FROM pipeline_stages WHERE system_key = 'NEW'",
                      Object[].class)
                  .getResultList()
                  .stream()
                  .findFirst()
                  .orElseThrow(
                      () -> new ClinicDataException(ClinicDataException.Reason.INVALID_DATA, null));
          boolean inserted =
              !session
                  .createNativeQuery(
                      """
                      INSERT INTO leads (id, contact_id, pipeline_id, stage_id, status,
                                         temperature, responsible_id, opened_at, created_by,
                                         updated_at, updated_by, origin_type, origin_source,
                                         origin_ad_id, origin_code, origin_title,
                                         origin_ctwa_clid)
                      VALUES (:id, :contact, :pipeline, :stage, 'OPEN', :temperature,
                              :responsible, :at, :actor, :at, :actor, :originType,
                              :originSource, :adId, :code, :title, :clid)
                      ON CONFLICT (contact_id) WHERE status = 'OPEN' DO NOTHING
                      RETURNING id
                      """,
                      UUID.class)
                  .setParameter("id", id)
                  .setParameter("contact", contactId)
                  .setParameter("pipeline", entry[1])
                  .setParameter("stage", entry[0])
                  .setParameter(
                      "temperature", temperature == null ? null : temperature.name(), String.class)
                  .setParameter("responsible", responsibleId, UUID.class)
                  .setParameter("originType", origin.type().name())
                  .setParameter("originSource", origin.source().name())
                  .setParameter("adId", origin.adId(), String.class)
                  .setParameter("code", origin.code(), String.class)
                  .setParameter("title", origin.title(), String.class)
                  .setParameter("clid", origin.ctwaClid(), String.class)
                  .setParameter("at", at)
                  .setParameter("actor", actor)
                  .getResultList()
                  .isEmpty();
          if (!inserted) {
            throw new LeadException(
                LeadException.Reason.ALREADY_OPEN, openLeadOf(session, contactId).orElse(null));
          }
          session.persist(
              new LeadEventEntity(
                  id, LeadEventType.CREATED, null, String.valueOf(entry[0]), null, actor, at));
          if (responsibleId != null) {
            session.persist(
                new LeadEventEntity(
                    id,
                    LeadEventType.RESPONSIBLE_CHANGED,
                    null,
                    responsibleId.toString(),
                    null,
                    actor,
                    at));
          }
          if (temperature != null) {
            session.persist(
                new LeadEventEntity(
                    id,
                    LeadEventType.TEMPERATURE_CHANGED,
                    null,
                    temperature.name(),
                    null,
                    actor,
                    at));
          }
          session.flush();
          return session.find(LeadEntity.class, id).toDomain();
        });
  }

  /** The lead, or empty for a missing id and for another clinic's id alike. */
  public Optional<Lead> find(ClinicAccess access, UUID id) {
    if (id == null) return Optional.empty();
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session ->
            Optional.ofNullable(session.find(LeadEntity.class, id)).map(LeadEntity::toDomain));
  }

  /** The contact's open lead, if any. */
  public Optional<Lead> openOf(ClinicAccess access, UUID contactId) {
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session ->
            openLeadOf(session, contactId)
                .map(leadId -> session.find(LeadEntity.class, leadId).toDomain()));
  }

  /** The lead's timeline, oldest first. */
  public List<LeadEvent> history(ClinicAccess access, UUID id) {
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          require(session, id);
          return session
              .createSelectionQuery(
                  "from LeadEventEntity e where e.leadId = :lead order by e.seq",
                  LeadEventEntity.class)
              .setParameter("lead", id)
              .getResultList()
              .stream()
              .map(LeadEventEntity::toDomain)
              .toList();
        });
  }

  /** Adds a note to the lead, open or closed. */
  public LeadNote addNote(ClinicAccess access, UUID id, String body) {
    Instant at = now.get();
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          require(session, id);
          LeadNoteEntity note = new LeadNoteEntity(id, body, access.subjectId(), at);
          session.persist(note);
          session.flush();
          return note.toDomain();
        });
  }

  /** The lead's notes, oldest first. */
  public List<LeadNote> notes(ClinicAccess access, UUID id) {
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          require(session, id);
          return session
              .createSelectionQuery(
                  "from LeadNoteEntity n where n.leadId = :lead order by n.seq",
                  LeadNoteEntity.class)
              .setParameter("lead", id)
              .getResultList()
              .stream()
              .map(LeadNoteEntity::toDomain)
              .toList();
        });
  }

  /**
   * One page of leads with their contact's name and phone, newest first, plus the total matching
   * the filters. Only constant fragments are concatenated; every value is a bound parameter.
   */
  public PageResult<LeadSummary> search(ClinicAccess access, LeadSearch search, PageQuery page) {
    Map<String, Object> parameters = new HashMap<>();
    StringBuilder where = new StringBuilder(" where l.status = :status");
    parameters.put("status", search.effectiveStatus());
    if (search.stageId() != null) {
      where.append(" and l.stageId = :stage");
      parameters.put("stage", search.stageId());
    }
    if (search.responsibleId() != null) {
      where.append(" and l.responsibleId = :responsible");
      parameters.put("responsible", search.responsibleId());
    }
    if (search.temperature() != null) {
      where.append(" and l.temperature = :temperature");
      parameters.put("temperature", search.temperature());
    }
    if (search.contactId() != null) {
      where.append(" and l.contactId = :contact");
      parameters.put("contact", search.contactId());
    }
    // Joins the contact module's entity by name (no Java dependency): renaming ContactEntity, its
    // name or phoneE164 breaks this query at run time, which LeadApiIT covers.
    String from = " from LeadEntity l join ContactEntity c on c.id = l.contactId" + where;
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          var rows =
              session.createSelectionQuery(
                  "select l, c.name, c.phoneE164" + from + " order by l.openedAt desc, l.id",
                  Object[].class);
          var count = session.createSelectionQuery("select count(l)" + from, Long.class);
          parameters.forEach(rows::setParameter);
          parameters.forEach(count::setParameter);
          List<LeadSummary> items =
              rows
                  .setFirstResult(page.offset())
                  .setMaxResults(page.pageSize())
                  .getResultList()
                  .stream()
                  .map(
                      row ->
                          new LeadSummary(
                              ((LeadEntity) row[0]).toDomain(), (String) row[1], (String) row[2]))
                  .toList();
          return new PageResult<>(items, count.getSingleResult());
        });
  }

  /**
   * Moves an open lead to another open stage of its funnel. The won stage is reached only by a sale
   * ({@link #closeWon}) and the lost one only with a reason ({@link #lose}).
   */
  public Lead moveStage(ClinicAccess access, UUID id, long expectedVersion, UUID stageId) {
    return change(
        access,
        id,
        expectedVersion,
        (session, row, actor, at) -> {
          Stage stage = stage(session, stageId, row.pipelineId());
          if (stage == null || stage.archived() || !"OPEN".equals(stage.kind())) {
            throw new LeadException(LeadException.Reason.STAGE_NOT_ALLOWED);
          }
          if (stageId.equals(row.stageId())) return;
          String from = row.stageId().toString();
          row.moveTo(stageId, actor, at);
          event(
              session, row, LeadEventType.STAGE_CHANGED, from, stageId.toString(), null, actor, at);
        });
  }

  public Lead changeTemperature(
      ClinicAccess access, UUID id, long expectedVersion, Temperature temperature) {
    Objects.requireNonNull(temperature, "temperature");
    return change(
        access,
        id,
        expectedVersion,
        (session, row, actor, at) -> {
          if (temperature == row.temperature()) return;
          String from = row.temperature() == null ? null : row.temperature().name();
          row.changeTemperature(temperature, actor, at);
          event(
              session,
              row,
              LeadEventType.TEMPERATURE_CHANGED,
              from,
              temperature.name(),
              null,
              actor,
              at);
        });
  }

  /** Sets or clears the responsible; the service has checked the membership. */
  public Lead assign(ClinicAccess access, UUID id, long expectedVersion, UUID responsibleId) {
    return change(
        access,
        id,
        expectedVersion,
        (session, row, actor, at) -> {
          if (Objects.equals(responsibleId, row.responsibleId())) return;
          String from = row.responsibleId() == null ? null : row.responsibleId().toString();
          row.assign(responsibleId, actor, at);
          event(
              session,
              row,
              LeadEventType.RESPONSIBLE_CHANGED,
              from,
              responsibleId == null ? null : responsibleId.toString(),
              null,
              actor,
              at);
        });
  }

  /**
   * Closes the lead as lost in the lost stage, with an active reason (CRM-06).
   *
   * @throws LeadException LOSS_REASON_NOT_FOUND
   */
  public Lead lose(ClinicAccess access, UUID id, long expectedVersion, UUID lossReasonId) {
    Objects.requireNonNull(lossReasonId, "lossReasonId");
    return change(
        access,
        id,
        expectedVersion,
        (session, row, actor, at) -> {
          boolean active =
              !session
                  .createNativeQuery(
                      // Share-locked: an archive of the reason waits, or this loss sees it
                      // archived.
                      "SELECT id FROM loss_reasons WHERE id = :id AND archived_at IS NULL"
                          + " FOR SHARE",
                      UUID.class)
                  .setParameter("id", lossReasonId)
                  .getResultList()
                  .isEmpty();
          if (!active) throw new LeadException(LeadException.Reason.LOSS_REASON_NOT_FOUND);
          UUID lost = terminal(session, row.pipelineId(), "LOST");
          String from = row.stageId().toString();
          row.close(LeadStatus.LOST, lost, lossReasonId, actor, at);
          event(session, row, LeadEventType.LOST, from, lost.toString(), lossReasonId, actor, at);
        });
  }

  /**
   * Closes the open lead read at {@code expectedVersion} as won, inside the sale registration's
   * transaction (CRM-035); the version is checked again when the session flushes.
   *
   * @throws LeadException NOT_FOUND, CLOSED or STALE
   */
  static LeadEntity closeWon(
      Session session, UUID id, long expectedVersion, UUID actor, Instant at) {
    LeadEntity row = require(session, id);
    if (row.status() != LeadStatus.OPEN) throw new LeadException(LeadException.Reason.CLOSED);
    if (row.version() != expectedVersion) throw new LeadException(LeadException.Reason.STALE);
    UUID won = terminal(session, row.pipelineId(), "WON");
    String from = row.stageId().toString();
    row.close(LeadStatus.WON, won, null, actor, at);
    event(session, row, LeadEventType.WON, from, won.toString(), null, actor, at);
    return row;
  }

  /**
   * Corrects the origin of the lead read at {@code expectedVersion}, open or closed, so reports
   * stay right after the sale (RN-03). The detected ad id and click id stay as evidence. The same
   * type, code and title change nothing and write no event.
   *
   * @throws LeadException NOT_FOUND or STALE
   */
  public Lead correctOrigin(ClinicAccess access, UUID id, long expectedVersion, LeadOrigin manual) {
    Objects.requireNonNull(manual, "manual");
    Instant at = now.get();
    UUID actor = access.subjectId();
    try {
      return persistence.inTransaction(
          ClinicTenant.of(access),
          session -> {
            LeadEntity row = require(session, id);
            if (row.version() != expectedVersion) {
              throw new LeadException(LeadException.Reason.STALE);
            }
            LeadOrigin current = row.origin();
            LeadOrigin corrected = current.correctedTo(manual);
            if (corrected.equals(current)) return row.toDomain();
            row.changeOrigin(corrected, actor, at);
            event(
                session,
                row,
                LeadEventType.ORIGIN_CHANGED,
                current.type().name(),
                corrected.type().name(),
                null,
                actor,
                at);
            session.flush();
            return row.toDomain();
          });
    } catch (ClinicDataException failure) {
      if (failure.reason() == ClinicDataException.Reason.STALE) {
        throw new LeadException(LeadException.Reason.STALE);
      }
      throw failure;
    }
  }

  /**
   * Records a later arrival of the contact with a detected origin, linked to its open lead if any;
   * never changes the lead (RN-02). Called by the WhatsApp worker (CRM-091).
   *
   * @throws LeadException CONTACT_UNAVAILABLE
   */
  public OriginTouch recordTouch(ClinicAccess access, UUID contactId, LeadOrigin origin) {
    Objects.requireNonNull(origin, "origin");
    Instant at = now.get();
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          requireActiveContact(session, contactId);
          UUID lead = openLeadOf(session, contactId).orElse(null);
          OriginTouchEntity touch = new OriginTouchEntity(contactId, lead, origin, at);
          session.persist(touch);
          session.flush();
          return touch.toDomain();
        });
  }

  /** The contact's latest origin touches, newest first; empty for another clinic's contact. */
  public List<OriginTouch> touches(ClinicAccess access, UUID contactId) {
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session ->
            session
                .createSelectionQuery(
                    "from OriginTouchEntity t where t.contactId = :contact order by t.seq desc",
                    OriginTouchEntity.class)
                .setParameter("contact", contactId)
                .setMaxResults(MAX_TOUCHES)
                .getResultList()
                .stream()
                .map(OriginTouchEntity::toDomain)
                .toList());
  }

  /** The touch list is returned without pagination, so it is capped. */
  public static final int MAX_TOUCHES = 100;

  @FunctionalInterface
  private interface Change {
    void apply(Session session, LeadEntity row, UUID actor, Instant at);
  }

  /** One change of an open lead at the version read, with its event, in one transaction. */
  private Lead change(ClinicAccess access, UUID id, long expectedVersion, Change change) {
    Instant at = now.get();
    try {
      return persistence.inTransaction(
          ClinicTenant.of(access),
          session -> {
            LeadEntity row = require(session, id);
            if (row.status() != LeadStatus.OPEN) {
              throw new LeadException(LeadException.Reason.CLOSED);
            }
            if (row.version() != expectedVersion) {
              throw new LeadException(LeadException.Reason.STALE);
            }
            change.apply(session, row, access.subjectId(), at);
            session.flush();
            return row.toDomain();
          });
    } catch (ClinicDataException failure) {
      if (failure.reason() == ClinicDataException.Reason.STALE) {
        throw new LeadException(LeadException.Reason.STALE);
      }
      // The stage trigger (BRL03) refused the stage: archived or of the wrong kind.
      if (failure.reason() == ClinicDataException.Reason.RULE_VIOLATION) {
        throw new LeadException(LeadException.Reason.STAGE_NOT_ALLOWED);
      }
      throw failure;
    }
  }

  private static void event(
      Session session,
      LeadEntity row,
      LeadEventType type,
      String from,
      String to,
      UUID lossReasonId,
      UUID actor,
      Instant at) {
    session.persist(new LeadEventEntity(row.id(), type, from, to, lossReasonId, actor, at));
  }

  private record Stage(String kind, boolean archived) {}

  private static Stage stage(Session session, UUID stageId, UUID pipelineId) {
    if (stageId == null) return null;
    return session
        .createNativeQuery(
            // Share-locked: archiving the stage (CRM-036) waits, or this move sees it archived.
            "SELECT kind, archived_at IS NOT NULL FROM pipeline_stages"
                + " WHERE id = :id AND pipeline_id = :pipeline FOR SHARE",
            Object[].class)
        .setParameter("id", stageId)
        .setParameter("pipeline", pipelineId)
        .getResultList()
        .stream()
        .findFirst()
        .map(row -> new Stage((String) row[0], (Boolean) row[1]))
        .orElse(null);
  }

  private static UUID terminal(Session session, UUID pipelineId, String kind) {
    return session
        .createNativeQuery(
            "SELECT id FROM pipeline_stages WHERE pipeline_id = :pipeline AND kind = :kind",
            UUID.class)
        .setParameter("pipeline", pipelineId)
        .setParameter("kind", kind)
        .getResultList()
        .stream()
        .findFirst()
        .orElseThrow(() -> new ClinicDataException(ClinicDataException.Reason.INVALID_DATA, null));
  }

  private static void requireActiveContact(Session session, UUID contactId) {
    List<Boolean> archived =
        contactId == null
            ? List.of()
            : session
                .createNativeQuery(
                    "SELECT archived_at IS NOT NULL FROM contacts WHERE id = :id FOR SHARE",
                    Boolean.class)
                .setParameter("id", contactId)
                .getResultList();
    if (archived.isEmpty() || archived.getFirst()) {
      throw new LeadException(LeadException.Reason.CONTACT_UNAVAILABLE);
    }
  }

  private static Optional<UUID> openLeadOf(Session session, UUID contactId) {
    return session
        .createNativeQuery(
            "SELECT id FROM leads WHERE contact_id = :contact AND status = 'OPEN'", UUID.class)
        .setParameter("contact", contactId)
        .getResultList()
        .stream()
        .findFirst();
  }

  private static LeadEntity require(Session session, UUID id) {
    LeadEntity row = id == null ? null : session.find(LeadEntity.class, id);
    if (row == null) throw new LeadException(LeadException.Reason.NOT_FOUND);
    return row;
  }
}
