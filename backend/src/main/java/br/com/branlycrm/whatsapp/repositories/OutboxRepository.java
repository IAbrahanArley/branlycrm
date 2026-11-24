package br.com.branlycrm.whatsapp.repositories;

import br.com.branlycrm.tenant.domain.ChannelAccess;
import br.com.branlycrm.tenant.persistence.ClinicPersistence;
import br.com.branlycrm.tenant.persistence.ClinicTenant;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * The worker side of {@code whatsapp_outbox} (CRM-084a, ADR-036, ADR-005), in the channel's clinic
 * database. A reply is claimed (SENDING, with a lease) in one transaction, sent outside any
 * transaction, and its result recorded in another. A lease that expires while SENDING means the
 * worker stopped between the request and the answer: the reply becomes UNCERTAIN and is never sent
 * again.
 */
@Repository
public class OutboxRepository {
  /** The customer service window, checked again right before sending. */
  public static final Duration WINDOW = Duration.ofHours(24);

  private final ClinicPersistence persistence;

  public OutboxRepository(ClinicPersistence persistence) {
    this.persistence = persistence;
  }

  /**
   * A reply ready to send; the text never appears in {@link #toString()}. A file (CRM-084b2) also
   * carries its media row: {@code body} is then the optional caption.
   */
  public record Outbound(
      UUID id,
      UUID conversationId,
      String body,
      String toDigits,
      Instant lastInboundAt,
      int attempts,
      OutboundMedia media) {
    public boolean isMedia() {
      return media != null;
    }

    @Override
    public String toString() {
      return "Outbound[id=" + id + "]";
    }
  }

  /**
   * The file of a MEDIA reply: where it is stored and, once uploaded, Meta's media id (a retry does
   * not upload it again).
   */
  public record OutboundMedia(
      UUID id, String storageKey, String contentType, String fileName, String metaMediaId) {
    /** image, video, audio or document. */
    public String messageType() {
      return OutboxRepository.messageType(contentType);
    }

    @Override
    public String toString() {
      return "OutboundMedia[id=" + id + "]";
    }
  }

  /** The WhatsApp message type of a sent file's detected type ({@code document} by default). */
  static String messageType(String contentType) {
    return java.util.Arrays.stream(br.com.branlycrm.whatsapp.domain.MediaType.values())
        .filter(type -> type.contentType().equals(contentType))
        .findFirst()
        .flatMap(br.com.branlycrm.whatsapp.domain.MediaType::outboundMessageType)
        .orElse("document");
  }

  /** SENDING replies whose lease expired: UNCERTAIN, never resent. Returns how many. */
  public int expireLeases(ChannelAccess access, Instant now) {
    return persistence.inTransaction(
        ClinicTenant.ofChannel(access),
        session ->
            session
                .createNativeMutationQuery(
                    "UPDATE whatsapp_outbox SET status = 'UNCERTAIN', lease_until = NULL,"
                        + " updated_at = :now WHERE status = 'SENDING' AND lease_until < :now")
                .setParameter("now", utc(now))
                .executeUpdate());
  }

  /** Claims the oldest due reply: SENDING, one more attempt, leased until {@code now + lease}. */
  public Optional<Outbound> claim(ChannelAccess access, Instant now, Duration lease) {
    return persistence.inTransaction(
        ClinicTenant.ofChannel(access),
        session -> {
          List<Object[]> rows =
              session
                  .createNativeQuery(
                      """
                      SELECT o.id, o.conversation_id, o.body, ct.phone_e164, c.last_inbound_at,
                             o.attempts, md.id, md.storage_key, md.content_type, md.file_name,
                             md.meta_media_id
                      FROM whatsapp_outbox o
                      JOIN whatsapp_conversations c ON c.id = o.conversation_id
                      JOIN contacts ct ON ct.id = c.contact_id
                      LEFT JOIN whatsapp_media md ON md.id = o.media_id
                      WHERE o.status = 'QUEUED' AND o.next_attempt_at <= :now
                      ORDER BY o.created_at, o.id
                      LIMIT 1
                      FOR UPDATE OF o SKIP LOCKED
                      """,
                      Object[].class)
                  .setParameter("now", utc(now))
                  .getResultList();
          if (rows.isEmpty()) return Optional.empty();
          Object[] row = rows.getFirst();
          int attempts = ((Number) row[5]).intValue() + 1;
          session
              .createNativeMutationQuery(
                  "UPDATE whatsapp_outbox SET status = 'SENDING', attempts = :attempts,"
                      + " lease_until = :until, updated_at = :now WHERE id = :id")
              .setParameter("attempts", attempts)
              .setParameter("until", utc(now.plus(lease)))
              .setParameter("now", utc(now))
              .setParameter("id", row[0])
              .executeUpdate();
          String phone = (String) row[3];
          return Optional.of(
              new Outbound(
                  (UUID) row[0],
                  (UUID) row[1],
                  (String) row[2],
                  phone.startsWith("+") ? phone.substring(1) : phone,
                  instant(row[4]),
                  attempts,
                  row[6] == null
                      ? null
                      : new OutboundMedia(
                          (UUID) row[6],
                          (String) row[7],
                          (String) row[8],
                          (String) row[9],
                          (String) row[10])));
        });
  }

  /** Meta's id of an uploaded file, kept for the retries of the same reply (CRM-084b2). */
  public void uploaded(ChannelAccess access, UUID mediaId, String metaMediaId, Instant at) {
    persistence.inTransaction(
        ClinicTenant.ofChannel(access),
        session ->
            session
                .createNativeMutationQuery(
                    "UPDATE whatsapp_media SET meta_media_id = :meta, updated_at = :at"
                        + " WHERE id = :id AND direction = 'OUT'")
                .setParameter("meta", metaMediaId)
                .setParameter("at", utc(at))
                .setParameter("id", mediaId)
                .executeUpdate());
  }

  /**
   * Meta accepted the reply: the outbox row becomes SENT (unless a status already moved it further)
   * and the message joins the conversation, once.
   */
  public void sent(ChannelAccess access, Outbound reply, String wamid, Instant at) {
    persistence.inTransaction(
        ClinicTenant.ofChannel(access),
        session -> {
          String current =
              session
                  .createNativeQuery(
                      "SELECT status FROM whatsapp_outbox WHERE id = :id FOR UPDATE", String.class)
                  .setParameter("id", reply.id())
                  .getSingleResult();
          // A failed status got here first: the conversation never shows it as sent.
          if (current.equals("FAILED")) return null;
          session
              .createNativeMutationQuery(
                  "UPDATE whatsapp_outbox SET status = 'SENT', wamid = :wamid, sent_at = :at,"
                      + " lease_until = NULL, updated_at = :at"
                      + " WHERE id = :id AND status IN ('SENDING', 'UNCERTAIN')")
              .setParameter("wamid", wamid)
              .setParameter("at", utc(at))
              .setParameter("id", reply.id())
              .executeUpdate();
          insertMessage(session, reply.id(), reply.conversationId(), wamid, "SENT", at);
          return null;
        });
  }

  /** Nothing reached Meta: back to QUEUED for another try at {@code next}. */
  public void retry(ChannelAccess access, UUID id, Instant next, Instant at) {
    update(
        access,
        id,
        "status = 'QUEUED', next_attempt_at = :next, lease_until = NULL, updated_at = :at",
        next,
        null,
        at);
  }

  /** Refused for good: FAILED with a stable code. */
  public void fail(ChannelAccess access, UUID id, String code, Instant at) {
    update(
        access,
        id,
        "status = 'FAILED', error_code = :code, lease_until = NULL, updated_at = :at",
        null,
        code,
        at);
  }

  /** May have reached Meta: UNCERTAIN, never resent; a delivery status reconciles it. */
  public void uncertain(ChannelAccess access, UUID id, Instant at) {
    update(
        access, id, "status = 'UNCERTAIN', lease_until = NULL, updated_at = :at", null, null, at);
  }

  /** Replies still to send (queued, due later or being sent). */
  public boolean hasPending(ChannelAccess access) {
    return persistence.inTransaction(
        ClinicTenant.ofChannel(access),
        session ->
            !session
                .createNativeQuery(
                    "SELECT 1 FROM whatsapp_outbox WHERE status IN ('QUEUED', 'SENDING') LIMIT 1",
                    Integer.class)
                .getResultList()
                .isEmpty());
  }

  private void update(
      ChannelAccess access, UUID id, String set, Instant next, String code, Instant at) {
    persistence.inTransaction(
        ClinicTenant.ofChannel(access),
        session -> {
          var update =
              session.createNativeMutationQuery(
                  "UPDATE whatsapp_outbox SET " + set + " WHERE id = :id AND status = 'SENDING'");
          if (set.contains(":next")) update.setParameter("next", utc(next));
          if (set.contains(":code")) update.setParameter("code", code);
          return update.setParameter("at", utc(at)).setParameter("id", id).executeUpdate();
        });
  }

  /**
   * The message of a reply in its conversation, at most once (unique outbox id and wamid). Text and
   * file come from the outbox row; a file's media row then points to the message, so the history
   * and the link show it like a received one (CRM-084b2).
   */
  static void insertMessage(
      org.hibernate.Session session,
      UUID outboxId,
      UUID conversationId,
      String wamid,
      String status,
      Instant at) {
    Object[] reply =
        session
            .createNativeQuery(
                "SELECT o.body, o.media_id, md.content_type FROM whatsapp_outbox o"
                    + " LEFT JOIN whatsapp_media md ON md.id = o.media_id WHERE o.id = :id",
                Object[].class)
            .setParameter("id", outboxId)
            .getSingleResult();
    String body = (String) reply[0];
    UUID mediaId = (UUID) reply[1];
    String contentType = (String) reply[2];
    // The same lock as the inbound worker: messages of one contact commit in their seq order, so
    // the inbox poll (seq > cursor) never skips one, even with more than one worker.
    session
        .createNativeQuery(
            "SELECT pg_advisory_xact_lock(hashtext('whatsapp-contact:' || ct.phone_e164))::text"
                + " FROM whatsapp_conversations c JOIN contacts ct ON ct.id = c.contact_id"
                + " WHERE c.id = :id",
            String.class)
        .setParameter("id", conversationId)
        .getResultList();
    session
        .createNativeMutationQuery(
            """
            INSERT INTO whatsapp_messages (id, conversation_id, wamid, direction, sent_by_app,
                                           message_type, body, media_mime, sent_at, created_at,
                                           status, outbox_id)
            VALUES (:id, :conversation, :wamid, 'OUT', false, :type, :body, :mime, :at, :at,
                    :status, :outbox)
            ON CONFLICT DO NOTHING
            """)
        .setParameter("id", UUID.randomUUID())
        .setParameter("conversation", conversationId)
        .setParameter("wamid", wamid)
        .setParameter("type", mediaId == null ? "text" : messageType(contentType))
        .setParameter("body", body, String.class)
        .setParameter("mime", contentType, String.class)
        .setParameter("at", utc(at))
        .setParameter("status", status)
        .setParameter("outbox", outboxId)
        .executeUpdate();
    if (mediaId != null) {
      session
          .createNativeMutationQuery(
              "UPDATE whatsapp_media SET message_id ="
                  + " (SELECT id FROM whatsapp_messages WHERE outbox_id = :outbox), updated_at = :at"
                  + " WHERE id = :media AND message_id IS NULL")
          .setParameter("outbox", outboxId)
          .setParameter("at", utc(at))
          .setParameter("media", mediaId)
          .executeUpdate();
    }
    session
        .createNativeMutationQuery(
            "UPDATE whatsapp_conversations SET last_message_at = GREATEST(last_message_at, :at)"
                + " WHERE id = :id")
        .setParameter("at", utc(at))
        .setParameter("id", conversationId)
        .executeUpdate();
  }

  static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  static Instant instant(Object value) {
    return switch (value) {
      case null -> null;
      case Instant instant -> instant;
      case OffsetDateTime offset -> offset.toInstant();
      case java.sql.Timestamp timestamp -> timestamp.toInstant();
      default -> throw new IllegalStateException("Unexpected timestamp type");
    };
  }
}
