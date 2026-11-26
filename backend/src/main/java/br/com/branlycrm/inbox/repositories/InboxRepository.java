package br.com.branlycrm.inbox.repositories;

import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.common.domain.PageResult;
import br.com.branlycrm.inbox.domain.ConversationSummary;
import br.com.branlycrm.inbox.domain.InboxFilter;
import br.com.branlycrm.inbox.domain.InboxMessage;
import br.com.branlycrm.inbox.domain.MessageCursor;
import br.com.branlycrm.inbox.domain.OutboxItem;
import br.com.branlycrm.inbox.domain.QueuedReply;
import br.com.branlycrm.inbox.exceptions.InboxException;
import br.com.branlycrm.inbox.exceptions.InboxException.Reason;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.persistence.ClinicPersistence;
import br.com.branlycrm.tenant.persistence.ClinicTenant;
import java.time.Clock;
import java.time.Duration;
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

  /** The customer service window of the WhatsApp Business Platform. */
  static final Duration WINDOW = Duration.ofHours(24);

  private static final String OUTBOX =
      "SELECT o.id, o.conversation_id, o.status, o.body, o.error_code, o.requested_by,"
          + " o.created_at, o.sent_at, o.kind, md.content_type, md.file_name"
          + " FROM whatsapp_outbox o LEFT JOIN whatsapp_media md ON md.id = o.media_id";

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
          if (before != null) condition = " AND m.seq < :cursor";
          if (after != null) condition = " AND m.seq > :cursor";
          String order = after != null ? "ASC" : "DESC";
          var query =
              session.createNativeQuery(
                  "SELECT m.id, m.seq, m.direction, m.sent_by_app, m.message_type, m.body,"
                      + " COALESCE(md.content_type, m.media_mime), m.sent_at, m.status, md.status"
                      + " FROM whatsapp_messages m"
                      + " LEFT JOIN whatsapp_media md ON md.message_id = m.id"
                      + " WHERE m.conversation_id = :conversation"
                      + condition
                      + " ORDER BY m.seq "
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

  /**
   * Queues a reply (CRM-084a) in one transaction with its rules: the conversation is locked; nobody
   * serving it means the author takes it; someone else serving it needs {@code mayOverride}; the
   * 24-hour window must be open. The same idempotency key returns the reply it already queued.
   *
   * @throws InboxException NOT_FOUND, TAKEN, WINDOW_CLOSED or IDEMPOTENCY_CONFLICT
   */
  public QueuedReply enqueue(
      ClinicAccess access,
      UUID conversationId,
      UUID idempotencyKey,
      String text,
      boolean mayOverride) {
    return queue(
        access,
        conversationId,
        idempotencyKey,
        mayOverride,
        true,
        (session, id, at) ->
            session
                .createNativeMutationQuery(
                    "INSERT INTO whatsapp_outbox (id, conversation_id, idempotency_key, kind,"
                        + " body, next_attempt_at, requested_by, created_at, updated_at)"
                        + " VALUES (:id, :conversation, :key, 'TEXT', :body, :at, :actor, :at,"
                        + " :at)")
                .setParameter("id", id)
                .setParameter("conversation", conversationId)
                .setParameter("key", idempotencyKey)
                .setParameter("body", text)
                .setParameter("at", utc(at))
                .setParameter("actor", access.subjectId())
                .executeUpdate());
  }

  /**
   * The checks of {@link #enqueue} without writing anything (CRM-084b2): run before a file is
   * stored, so a refused send leaves no object behind. The checks run again when queueing.
   *
   * @return the item the same idempotency key already queued, if any
   * @throws InboxException NOT_FOUND, TAKEN, WINDOW_CLOSED or IDEMPOTENCY_CONFLICT
   */
  public Optional<QueuedReply> checkReply(
      ClinicAccess access, UUID conversationId, UUID idempotencyKey, boolean mayOverride) {
    return checkReply(access, conversationId, idempotencyKey, mayOverride, true);
  }

  /** {@link #checkReply}, with the 24-hour window checked or not (templates, CRM-085). */
  public Optional<QueuedReply> checkReply(
      ClinicAccess access,
      UUID conversationId,
      UUID idempotencyKey,
      boolean mayOverride,
      boolean windowRequired) {
    QueuedReply checked =
        queue(access, conversationId, idempotencyKey, mayOverride, windowRequired, null);
    return checked.created() ? Optional.empty() : Optional.of(checked);
  }

  /**
   * Queues a file already stored in the bucket (CRM-084b2), with the rules of {@link #enqueue}: the
   * media row (OUT, STORED) and the outbox item in one transaction.
   *
   * @throws InboxException NOT_FOUND, TAKEN, WINDOW_CLOSED or IDEMPOTENCY_CONFLICT
   */
  public QueuedReply enqueueMedia(
      ClinicAccess access,
      UUID conversationId,
      UUID idempotencyKey,
      String caption,
      OutboundFile file,
      boolean mayOverride) {
    return queue(
        access,
        conversationId,
        idempotencyKey,
        mayOverride,
        true,
        (session, id, at) -> {
          session
              .createNativeMutationQuery(
                  """
                  INSERT INTO whatsapp_media (id, direction, status, content_type, size_bytes,
                                              sha256, storage_key, file_name, next_attempt_at,
                                              created_at, updated_at)
                  VALUES (:id, 'OUT', 'STORED', :type, :size, :sha, :key, :name, :at, :at, :at)
                  """)
              .setParameter("id", file.mediaId())
              .setParameter("type", file.contentType())
              .setParameter("size", file.size())
              .setParameter("sha", file.sha256())
              .setParameter("key", file.storageKey())
              .setParameter("name", file.fileName(), String.class)
              .setParameter("at", utc(at))
              .executeUpdate();
          return session
              .createNativeMutationQuery(
                  "INSERT INTO whatsapp_outbox (id, conversation_id, idempotency_key, kind, body,"
                      + " media_id, next_attempt_at, requested_by, created_at, updated_at)"
                      + " VALUES (:id, :conversation, :key, 'MEDIA', :body, :media, :at, :actor,"
                      + " :at, :at)")
              .setParameter("id", id)
              .setParameter("conversation", conversationId)
              .setParameter("key", idempotencyKey)
              .setParameter("body", caption, String.class)
              .setParameter("media", file.mediaId())
              .setParameter("at", utc(at))
              .setParameter("actor", access.subjectId())
              .executeUpdate();
        });
  }

  /**
   * Queues an approved template (CRM-085), inside or outside the 24-hour window, with the other
   * rules of {@link #enqueue}. In the same transaction: the template must still be APPROVED and
   * supported; a MARKETING template is refused to a contact whose latest WhatsApp consent is
   * OPT_OUT; and the day's counter (clinic time zone) goes up by one only while under {@code
   * dailyLimit}.
   *
   * @param text the template as the patient sees it, for the history
   * @param values the values as JSON: {"header": {...}, "body": {...}}
   * @throws InboxException NOT_FOUND, TAKEN, IDEMPOTENCY_CONFLICT, TEMPLATE_NOT_FOUND,
   *     TEMPLATE_NOT_APPROVED, TEMPLATE_OPTED_OUT or TEMPLATE_LIMIT
   */
  public QueuedReply enqueueTemplate(
      ClinicAccess access,
      UUID conversationId,
      UUID idempotencyKey,
      UUID templateId,
      String text,
      String values,
      int dailyLimit,
      boolean mayOverride) {
    return queue(
        access,
        conversationId,
        idempotencyKey,
        mayOverride,
        false,
        (session, id, at) -> {
          List<Object[]> template =
              session
                  .createNativeQuery(
                      "SELECT status, supported, category FROM whatsapp_templates WHERE id = :id"
                          + " FOR SHARE",
                      Object[].class)
                  .setParameter("id", templateId)
                  .getResultList();
          if (template.isEmpty()) throw new InboxException(Reason.TEMPLATE_NOT_FOUND);
          if (!"APPROVED".equals(template.getFirst()[0])
              || !Boolean.TRUE.equals(template.getFirst()[1])) {
            throw new InboxException(Reason.TEMPLATE_NOT_APPROVED);
          }
          if ("MARKETING".equals(template.getFirst()[2]) && optedOut(session, conversationId)) {
            throw new InboxException(Reason.TEMPLATE_OPTED_OUT);
          }
          int counted =
              session
                  .createNativeMutationQuery(
                      """
                      INSERT INTO whatsapp_template_usage (day, queued)
                      VALUES ((CAST(:at AS timestamptz)
                               AT TIME ZONE (SELECT time_zone FROM clinic_settings))::date, 1)
                      ON CONFLICT (day) DO UPDATE SET queued = whatsapp_template_usage.queued + 1
                      WHERE whatsapp_template_usage.queued < :limit
                      """)
                  .setParameter("at", utc(at))
                  .setParameter("limit", dailyLimit)
                  .executeUpdate();
          if (counted == 0 || dailyLimit <= 0) throw new InboxException(Reason.TEMPLATE_LIMIT);
          return session
              .createNativeMutationQuery(
                  "INSERT INTO whatsapp_outbox (id, conversation_id, idempotency_key, kind, body,"
                      + " template_id, template_values, next_attempt_at, requested_by,"
                      + " created_at, updated_at) VALUES (:id, :conversation, :key, 'TEMPLATE',"
                      + " :body, :template, CAST(:values AS jsonb), :at, :actor, :at, :at)")
              .setParameter("id", id)
              .setParameter("conversation", conversationId)
              .setParameter("key", idempotencyKey)
              .setParameter("body", text)
              .setParameter("template", templateId)
              .setParameter("values", values)
              .setParameter("at", utc(at))
              .setParameter("actor", access.subjectId())
              .executeUpdate();
        });
  }

  /** The latest WhatsApp consent of the conversation's contact is OPT_OUT. */
  private static boolean optedOut(Session session, UUID conversationId) {
    return !session
        .createNativeQuery(
            """
            SELECT 1 FROM (
                SELECT cc.status FROM contact_consents cc
                JOIN whatsapp_conversations c ON c.contact_id = cc.contact_id
                WHERE c.id = :conversation AND cc.channel = 'WHATSAPP'
                ORDER BY cc.recorded_at DESC, cc.id DESC LIMIT 1) latest
            WHERE latest.status = 'OPT_OUT'
            """,
            Integer.class)
        .setParameter("conversation", conversationId)
        .getResultList()
        .isEmpty();
  }

  /** The clinic's synced templates, except deleted ones; approved and supported first. */
  public List<TemplateOption> templates(ClinicAccess access) {
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session ->
            session
                .createNativeQuery(
                    TEMPLATES
                        + " WHERE status <> 'DELETED'"
                        + " ORDER BY (status = 'APPROVED' AND supported) DESC, name, language",
                    Object[].class)
                .getResultList()
                .stream()
                .map(InboxRepository::templateOption)
                .toList());
  }

  /** One template of the clinic (deleted ones included, so a send names the right refusal). */
  public Optional<TemplateOption> template(ClinicAccess access, UUID id) {
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session ->
            session
                .createNativeQuery(TEMPLATES + " WHERE id = :id", Object[].class)
                .setParameter("id", id)
                .getResultList()
                .stream()
                .findFirst()
                .map(InboxRepository::templateOption));
  }

  private static final String TEMPLATES =
      "SELECT id, meta_template_id, name, language, category, status, supported,"
          + " parameter_format, header_text, body_text, footer_text, header_params::text,"
          + " body_params::text, synced_at FROM whatsapp_templates";

  private static final tools.jackson.databind.json.JsonMapper JSON =
      tools.jackson.databind.json.JsonMapper.builder().build();

  private static TemplateOption templateOption(Object[] row) {
    return new TemplateOption(
        (UUID) row[0],
        new br.com.branlycrm.whatsapp.domain.MessageTemplate(
            (String) row[1],
            (String) row[2],
            (String) row[3],
            (String) row[4],
            (String) row[5],
            (Boolean) row[6],
            "NAMED".equals(row[7]),
            (String) row[8],
            (String) row[9],
            (String) row[10],
            keys((String) row[11]),
            keys((String) row[12])),
        instant(row[13]));
  }

  private static List<String> keys(String json) {
    List<String> keys = new ArrayList<>();
    JSON.readTree(json).forEach(node -> keys.add(node.asText()));
    return List.copyOf(keys);
  }

  /** A synced template and our id for it (CRM-085). */
  public record TemplateOption(
      UUID id, br.com.branlycrm.whatsapp.domain.MessageTemplate template, Instant syncedAt) {}

  /**
   * A file stored in the bucket by the API, to send (CRM-084b2); the key never leaves the server.
   */
  public record OutboundFile(
      UUID mediaId,
      String storageKey,
      String contentType,
      long size,
      String sha256,
      String fileName) {
    @Override
    public String toString() {
      return "OutboundFile[id=" + mediaId + "]";
    }
  }

  /** Writes the outbox row (and what it needs) once the rules passed. */
  @FunctionalInterface
  private interface OutboxWriter {
    int write(Session session, UUID id, Instant at);
  }

  /**
   * The rules of a reply, in one transaction with the conversation locked; with a null {@code
   * writer} nothing is written and {@code created} is true when the key is new.
   */
  private QueuedReply queue(
      ClinicAccess access,
      UUID conversationId,
      UUID idempotencyKey,
      boolean mayOverride,
      boolean windowRequired,
      OutboxWriter writer) {
    UUID actor = access.subjectId();
    Instant at = now();
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          List<Object[]> rows =
              session
                  .createNativeQuery(
                      "SELECT channel_id, assignee_id, last_inbound_at FROM whatsapp_conversations"
                          + " WHERE id = :id FOR UPDATE",
                      Object[].class)
                  .setParameter("id", conversationId)
                  .getResultList();
          if (rows.isEmpty()) throw new InboxException(Reason.NOT_FOUND);
          UUID channel = (UUID) rows.getFirst()[0];
          UUID assignee = (UUID) rows.getFirst()[1];
          Instant lastInbound = instant(rows.getFirst()[2]);
          Optional<OutboxItem> existing = outboxByKey(session, idempotencyKey);
          if (existing.isPresent()) {
            if (!existing.get().conversationId().equals(conversationId)) {
              throw new InboxException(Reason.IDEMPOTENCY_CONFLICT);
            }
            return new QueuedReply(existing.get(), channel, false);
          }
          if (assignee != null && !assignee.equals(actor) && !mayOverride) {
            throw new InboxException(Reason.TAKEN);
          }
          if (windowRequired && (lastInbound == null || lastInbound.isBefore(at.minus(WINDOW)))) {
            throw new InboxException(Reason.WINDOW_CLOSED);
          }
          if (writer == null) return new QueuedReply(null, channel, true);
          if (assignee == null) {
            session
                .createNativeMutationQuery(
                    "UPDATE whatsapp_conversations SET status = 'IN_SERVICE', assignee_id = :actor,"
                        + " assigned_at = :at, version = version + 1 WHERE id = :id")
                .setParameter("actor", actor)
                .setParameter("at", utc(at))
                .setParameter("id", conversationId)
                .executeUpdate();
            event(session, conversationId, "TAKEN", actor, actor, at);
          }
          writer.write(session, UUID.randomUUID(), at);
          return new QueuedReply(outboxByKey(session, idempotencyKey).orElseThrow(), channel, true);
        });
  }

  /**
   * Replies of the conversation that still need attention: queued, sending, uncertain, or failed in
   * the last 7 days; newest first.
   *
   * @throws InboxException NOT_FOUND
   */
  public List<OutboxItem> pendingReplies(ClinicAccess access, UUID conversationId) {
    Instant since = now().minus(Duration.ofDays(7));
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          requireExists(session, conversationId);
          return session
              .createNativeQuery(
                  OUTBOX
                      + " WHERE o.conversation_id = :conversation"
                      + " AND (o.status IN ('QUEUED', 'SENDING', 'UNCERTAIN')"
                      + " OR (o.status = 'FAILED' AND o.created_at >= :since))"
                      + " ORDER BY o.created_at DESC, o.id LIMIT 50",
                  Object[].class)
              .setParameter("conversation", conversationId)
              .setParameter("since", utc(since))
              .getResultList()
              .stream()
              .map(InboxRepository::outbox)
              .toList();
        });
  }

  private static Optional<OutboxItem> outboxByKey(Session session, UUID key) {
    return session
        .createNativeQuery(OUTBOX + " WHERE o.idempotency_key = :key", Object[].class)
        .setParameter("key", key)
        .getResultList()
        .stream()
        .findFirst()
        .map(InboxRepository::outbox);
  }

  private static OutboxItem outbox(Object[] row) {
    return new OutboxItem(
        (UUID) row[0],
        (UUID) row[1],
        (String) row[2],
        (String) row[3],
        (String) row[4],
        (UUID) row[5],
        instant(row[6]),
        instant(row[7]),
        (String) row[8],
        (String) row[9],
        (String) row[10]);
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
        instant(row[7]),
        (String) row[8],
        (String) row[9]);
  }

  /**
   * The stored file of a message of the conversation (CRM-084b1); the storage key comes from this
   * row only.
   *
   * @return empty for a message without media, or of another conversation or clinic
   */
  public Optional<MediaFile> media(ClinicAccess access, UUID conversationId, UUID messageId) {
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session ->
            session
                .createNativeQuery(
                    "SELECT md.status, md.storage_key, md.content_type, md.error_code"
                        + " FROM whatsapp_media md JOIN whatsapp_messages m ON m.id = md.message_id"
                        + " WHERE m.id = :message AND m.conversation_id = :conversation",
                    Object[].class)
                .setParameter("message", messageId)
                .setParameter("conversation", conversationId)
                .getResultList()
                .stream()
                .findFirst()
                .map(
                    row ->
                        new MediaFile(
                            (String) row[0], (String) row[1], (String) row[2], (String) row[3])));
  }

  /** A media row as the link needs it; the key never leaves the server. */
  public record MediaFile(String status, String storageKey, String contentType, String errorCode) {
    @Override
    public String toString() {
      return "MediaFile[status=" + status + "]";
    }
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
