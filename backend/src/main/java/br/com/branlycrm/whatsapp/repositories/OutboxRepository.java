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

  /** A reply ready to send; the text never appears in {@link #toString()}. */
  public record Outbound(
      UUID id,
      UUID conversationId,
      String body,
      String toDigits,
      Instant lastInboundAt,
      int attempts) {
    @Override
    public String toString() {
      return "Outbound[id=" + id + "]";
    }
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
                             o.attempts
                      FROM whatsapp_outbox o
                      JOIN whatsapp_conversations c ON c.id = o.conversation_id
                      JOIN contacts ct ON ct.id = c.contact_id
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
                  attempts));
        });
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
          insertMessage(
              session, reply.id(), reply.conversationId(), wamid, reply.body(), "SENT", at);
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

  /** The message of a reply in its conversation, at most once (unique outbox id and wamid). */
  static void insertMessage(
      org.hibernate.Session session,
      UUID outboxId,
      UUID conversationId,
      String wamid,
      String body,
      String status,
      Instant at) {
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
                                           message_type, body, sent_at, created_at, status,
                                           outbox_id)
            VALUES (:id, :conversation, :wamid, 'OUT', false, 'text', :body, :at, :at, :status,
                    :outbox)
            ON CONFLICT DO NOTHING
            """)
        .setParameter("id", UUID.randomUUID())
        .setParameter("conversation", conversationId)
        .setParameter("wamid", wamid)
        .setParameter("body", body)
        .setParameter("at", utc(at))
        .setParameter("status", status)
        .setParameter("outbox", outboxId)
        .executeUpdate();
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
