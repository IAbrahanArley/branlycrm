package br.com.branlycrm.inbox.repositories;

import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.common.domain.PageResult;
import br.com.branlycrm.inbox.domain.ConversationSummary;
import br.com.branlycrm.inbox.domain.InboxFilter;
import br.com.branlycrm.inbox.domain.InboxMessage;
import br.com.branlycrm.inbox.domain.MessageCursor;
import br.com.branlycrm.inbox.exceptions.InboxException;
import br.com.branlycrm.inbox.exceptions.InboxException.Reason;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.persistence.ClinicPersistence;
import br.com.branlycrm.tenant.persistence.ClinicTenant;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.Session;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;

/**
 * Conversations and messages of the clinic authorized by a {@link ClinicAccess} (CRM-083). The
 * worker writes conversations and messages; this repository reads them and changes only who serves
 * a conversation and its mark, each change with an event.
 */
@Repository
public class InboxRepository {
  /**
   * The summary of the conversations selected by {@code %s} (with {@code total}): contact and
   * latest message are joined only for those rows, so a poll never reads every conversation's last
   * message.
   */
  private static final String SUMMARY =
      """
      SELECT c.id, c.contact_id, ct.name, ct.phone_e164, c.status, c.assignee_id, c.assigned_at,
             c.needs_human, c.last_message_at, c.last_inbound_at, m.direction, m.message_type,
             left(m.body, %d), c.version, ct.archived_at IS NOT NULL, c.total
      FROM (%%s) c
      JOIN contacts ct ON ct.id = c.contact_id
      LEFT JOIN LATERAL (
          SELECT direction, message_type, body FROM whatsapp_messages
          WHERE conversation_id = c.id ORDER BY seq DESC LIMIT 1) m ON true
      ORDER BY c.last_message_at DESC, c.id
      """
          .formatted(ConversationSummary.PREVIEW_MAX);

  private static final String COLUMNS =
      "id, contact_id, status, assignee_id, assigned_at, needs_human, last_message_at,"
          + " last_inbound_at, version";

  private final ClinicPersistence persistence;
  private final Clock clock;

  public InboxRepository(ClinicPersistence persistence, ObjectProvider<Clock> clock) {
    this.persistence = persistence;
    this.clock = clock.getIfAvailable(Clock::systemUTC);
  }

  /** Most recent first; total and page from the same statement. */
  public PageResult<ConversationSummary> search(
      ClinicAccess access, InboxFilter filter, PageQuery page) {
    StringBuilder where = new StringBuilder(" WHERE true");
    if (filter.status() != null) where.append(" AND c.status = :status");
    if (filter.assigneeId() != null) where.append(" AND c.assignee_id = :assignee");
    if (filter.unassigned()) where.append(" AND c.assignee_id IS NULL");
    if (filter.needsHuman() != null) where.append(" AND c.needs_human = :needsHuman");
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          String selected =
              "SELECT "
                  + COLUMNS
                  + ", count(*) OVER () AS total FROM whatsapp_conversations c"
                  + where
                  + " ORDER BY last_message_at DESC, id LIMIT :limit OFFSET :offset";
          var query = session.createNativeQuery(SUMMARY.formatted(selected), Object[].class);
          if (filter.status() != null) query.setParameter("status", filter.status());
          if (filter.assigneeId() != null) query.setParameter("assignee", filter.assigneeId());
          if (filter.needsHuman() != null) query.setParameter("needsHuman", filter.needsHuman());
          List<Object[]> rows =
              query
                  .setParameter("limit", page.pageSize())
                  .setParameter("offset", page.offset())
                  .getResultList();
          long total;
          if (!rows.isEmpty()) {
            total = ((Number) rows.getFirst()[15]).longValue();
          } else if (page.offset() == 0) {
            total = 0;
          } else {
            var count =
                session.createNativeQuery(
                    "SELECT count(*) FROM whatsapp_conversations c" + where, Long.class);
            if (filter.status() != null) count.setParameter("status", filter.status());
            if (filter.assigneeId() != null) count.setParameter("assignee", filter.assigneeId());
            if (filter.needsHuman() != null) {
              count.setParameter("needsHuman", filter.needsHuman());
            }
            total = count.getSingleResult();
          }
          return new PageResult<>(rows.stream().map(InboxRepository::summary).toList(), total);
        });
  }

  /**
   * @throws InboxException NOT_FOUND
   */
  public ConversationSummary get(ClinicAccess access, UUID id) {
    return persistence.inTransaction(ClinicTenant.of(access), session -> require(session, id));
  }

  /**
   * A slice of the history, in chronological order: the latest {@code limit} messages, the ones
   * before {@code before}, or the ones after {@code after} (the poll of new messages).
   *
   * @throws InboxException NOT_FOUND
   */
  public List<InboxMessage> messages(
      ClinicAccess access, UUID id, MessageCursor before, MessageCursor after, int limit) {
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          requireExists(session, id);
          String condition = "";
          if (before != null) condition = " AND seq < :cursor";
          if (after != null) condition = " AND seq > :cursor";
          String order = after != null ? "ASC" : "DESC";
          var query =
              session.createNativeQuery(
                  "SELECT id, seq, direction, sent_by_app, message_type, body, media_mime, sent_at"
                      + " FROM whatsapp_messages WHERE conversation_id = :conversation"
                      + condition
                      + " ORDER BY seq "
                      + order
                      + " LIMIT :limit",
                  Object[].class);
          MessageCursor cursor = after != null ? after : before;
          if (cursor != null) query.setParameter("cursor", cursor.seq());
          List<InboxMessage> messages =
              new ArrayList<>(
                  query
                      .setParameter("conversation", id)
                      .setParameter("limit", limit)
                      .getResultList()
                      .stream()
                      .map(InboxRepository::message)
                      .toList());
          if (after == null) Collections.reverse(messages);
          return List.copyOf(messages);
        });
  }

  /**
   * Takes a conversation nobody serves, in one conditional statement: two members taking it at once
   * never both succeed. Taking one's own conversation again changes nothing.
   *
   * @throws InboxException NOT_FOUND or TAKEN
   */
  public ConversationSummary take(ClinicAccess access, UUID id) {
    UUID actor = access.subjectId();
    Instant at = now();
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          int taken =
              session
                  .createNativeMutationQuery(
                      "UPDATE whatsapp_conversations SET status = 'IN_SERVICE',"
                          + " assignee_id = :actor, assigned_at = :at, version = version + 1"
                          + " WHERE id = :id AND assignee_id IS NULL")
                  .setParameter("actor", actor)
                  .setParameter("at", utc(at))
                  .setParameter("id", id)
                  .executeUpdate();
          ConversationSummary current = require(session, id);
          if (taken == 0) {
            if (actor.equals(current.assigneeId())) return current;
            throw new InboxException(Reason.TAKEN);
          }
          event(session, id, "TAKEN", actor, actor, at);
          return current;
        });
  }

  /**
   * Releases the conversation back to WAITING.
   *
   * @param othersToo the actor may release a colleague's conversation (INBOX_ASSIGN)
   * @throws InboxException NOT_FOUND, STALE, NOT_ASSIGNED or NOT_YOURS
   */
  public ConversationSummary release(
      ClinicAccess access, UUID id, long expectedVersion, boolean othersToo) {
    UUID actor = access.subjectId();
    Instant at = now();
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          ConversationSummary current = requireVersion(session, id, expectedVersion);
          if (current.assigneeId() == null) throw new InboxException(Reason.NOT_ASSIGNED);
          if (!othersToo && !actor.equals(current.assigneeId())) {
            throw new InboxException(Reason.NOT_YOURS);
          }
          change(
              session,
              id,
              expectedVersion,
              "status = 'WAITING', assignee_id = NULL, assigned_at = NULL",
              null,
              null,
              at);
          event(session, id, "RELEASED", null, actor, at);
          return require(session, id);
        });
  }

  /**
   * Hands the conversation to {@code assignee}, already checked as an active member who replies.
   *
   * @throws InboxException NOT_FOUND or STALE
   */
  public ConversationSummary assign(
      ClinicAccess access, UUID id, long expectedVersion, UUID assignee) {
    UUID actor = access.subjectId();
    Instant at = now();
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          ConversationSummary current = requireVersion(session, id, expectedVersion);
          if (assignee.equals(current.assigneeId())) return current;
          change(
              session,
              id,
              expectedVersion,
              "status = 'IN_SERVICE', assignee_id = :assignee, assigned_at = :at",
              assignee,
              null,
              at);
          event(session, id, "ASSIGNED", assignee, actor, at);
          return require(session, id);
        });
  }

  /**
   * Marks or clears "needs a person"; the same value again changes nothing.
   *
   * @throws InboxException NOT_FOUND or STALE
   */
  public ConversationSummary markNeedsHuman(
      ClinicAccess access, UUID id, long expectedVersion, boolean needsHuman) {
    UUID actor = access.subjectId();
    Instant at = now();
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          ConversationSummary current = requireVersion(session, id, expectedVersion);
          if (current.needsHuman() == needsHuman) return current;
          change(session, id, expectedVersion, "needs_human = :flag", null, needsHuman, at);
          event(session, id, needsHuman ? "NEEDS_HUMAN" : "HUMAN_DONE", null, actor, at);
          return require(session, id);
        });
  }

  private static void change(
      Session session,
      UUID id,
      long expectedVersion,
      String set,
      UUID assignee,
      Boolean flag,
      Instant at) {
    var update =
        session.createNativeMutationQuery(
            "UPDATE whatsapp_conversations SET "
                + set
                + ", version = version + 1 WHERE id = :id AND version = :version");
    if (set.contains(":assignee")) update.setParameter("assignee", assignee);
    if (set.contains(":at")) update.setParameter("at", utc(at));
    if (set.contains(":flag")) update.setParameter("flag", flag);
    int changed =
        update.setParameter("id", id).setParameter("version", expectedVersion).executeUpdate();
    if (changed == 0) throw new InboxException(Reason.STALE);
  }

  private static void event(
      Session session, UUID conversation, String type, UUID assignee, UUID actor, Instant at) {
    session
        .createNativeMutationQuery(
            "INSERT INTO whatsapp_conversation_events (id, conversation_id, type, assignee_id,"
                + " actor_id, occurred_at) VALUES (:id, :conversation, :type, :assignee, :actor,"
                + " :at)")
        .setParameter("id", UUID.randomUUID())
        .setParameter("conversation", conversation)
        .setParameter("type", type)
        .setParameter("assignee", assignee, UUID.class)
        .setParameter("actor", actor)
        .setParameter("at", utc(at))
        .executeUpdate();
  }

  private static ConversationSummary requireVersion(Session session, UUID id, long version) {
    ConversationSummary current = require(session, id);
    if (current.version() != version) throw new InboxException(Reason.STALE);
    return current;
  }

  private static ConversationSummary require(Session session, UUID id) {
    return find(session, id).orElseThrow(() -> new InboxException(Reason.NOT_FOUND));
  }

  /** Existence only, for the history poll: no join. */
  private static void requireExists(Session session, UUID id) {
    boolean exists =
        id != null
            && !session
                .createNativeQuery(
                    "SELECT 1 FROM whatsapp_conversations WHERE id = :id", Integer.class)
                .setParameter("id", id)
                .getResultList()
                .isEmpty();
    if (!exists) throw new InboxException(Reason.NOT_FOUND);
  }

  private static Optional<ConversationSummary> find(Session session, UUID id) {
    if (id == null) return Optional.empty();
    String one = "SELECT " + COLUMNS + ", 0 AS total FROM whatsapp_conversations WHERE id = :id";
    return session
        .createNativeQuery(SUMMARY.formatted(one), Object[].class)
        .setParameter("id", id)
        .getResultList()
        .stream()
        .findFirst()
        .map(InboxRepository::summary);
  }

  private static ConversationSummary summary(Object[] row) {
    return new ConversationSummary(
        (UUID) row[0],
        (UUID) row[1],
        (String) row[2],
        (String) row[3],
        (String) row[4],
        (UUID) row[5],
        instant(row[6]),
        (Boolean) row[7],
        instant(row[8]),
        instant(row[9]),
        (String) row[10],
        (String) row[11],
        (String) row[12],
        ((Number) row[13]).longValue(),
        (Boolean) row[14]);
  }

  private static InboxMessage message(Object[] row) {
    return new InboxMessage(
        (UUID) row[0],
        ((Number) row[1]).longValue(),
        (String) row[2],
        (Boolean) row[3],
        (String) row[4],
        (String) row[5],
        (String) row[6],
        instant(row[7]));
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static Instant instant(Object value) {
    return switch (value) {
      case null -> null;
      case Instant instant -> instant;
      case OffsetDateTime offset -> offset.toInstant();
      case java.sql.Timestamp timestamp -> timestamp.toInstant();
      default -> throw new IllegalStateException("Unexpected timestamp type");
    };
  }
}
