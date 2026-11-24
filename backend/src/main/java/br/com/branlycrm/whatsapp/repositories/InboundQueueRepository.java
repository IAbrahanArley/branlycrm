package br.com.branlycrm.whatsapp.repositories;

import br.com.branlycrm.contact.domain.ContactCreation;
import br.com.branlycrm.contact.repositories.ContactRepository;
import br.com.branlycrm.pipeline.domain.LeadOrigin;
import br.com.branlycrm.pipeline.exceptions.LeadException;
import br.com.branlycrm.pipeline.repositories.LeadRepository;
import br.com.branlycrm.tenant.domain.ChannelAccess;
import br.com.branlycrm.tenant.exceptions.ClinicDataException;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.persistence.ClinicPersistence;
import br.com.branlycrm.tenant.persistence.ClinicTenant;
import br.com.branlycrm.tenant.secrets.TenantSecretException;
import br.com.branlycrm.whatsapp.domain.InboundAction;
import br.com.branlycrm.whatsapp.domain.InboundHandler;
import br.com.branlycrm.whatsapp.domain.InboundKind;
import br.com.branlycrm.whatsapp.domain.InboundMessage;
import br.com.branlycrm.whatsapp.domain.StoredEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.Session;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;

/**
 * The worker side of {@code whatsapp_inbound_events} (CRM-082b, ADR-036). Each event is applied in
 * one clinic transaction: reserved with {@code FOR UPDATE SKIP LOCKED}, serialized per contact by
 * an advisory lock on the canonical phone, and marked processed with its writes. A failure rolls
 * the whole transaction back and is then recorded in a transaction of its own:
 *
 * <ul>
 *   <li>a failure of the clinic itself (database down, migration pending, identity, secret) only
 *       delays the event: it never spends an attempt, so a clinic outage loses nothing;
 *   <li>any other failure spends an attempt, with an exponential delay, up to {@link
 *       #MAX_ATTEMPTS}; an exhausted event stays stored, with its code, for a person to reprocess.
 * </ul>
 *
 * Events of one contact are applied in Meta's order: an event waits while an earlier one of the
 * same contact is still pending and retryable, so a later message never takes the lead's origin
 * from a failed ad click.
 */
@Repository
public class InboundQueueRepository {
  public static final int MAX_ATTEMPTS = 8;
  static final int RETENTION_BATCH = 1000;
  private static final Duration FIRST_DELAY = Duration.ofSeconds(30);
  private static final Duration MAX_DELAY = Duration.ofHours(1);

  private final ClinicPersistence persistence;
  private final ContactRepository contacts;
  private final LeadRepository leads;
  private final Clock clock;

  public InboundQueueRepository(
      ClinicPersistence persistence,
      ContactRepository contacts,
      LeadRepository leads,
      ObjectProvider<Clock> clock) {
    this.persistence = persistence;
    this.contacts = contacts;
    this.leads = leads;
    this.clock = clock.getIfAvailable(Clock::systemUTC);
  }

  /** What happened to the next due event. */
  public sealed interface Outcome {
    /** No event is due now. */
    record Empty() implements Outcome {}

    /** Applied, or skipped with a reason (null when there was nothing to report). */
    record Processed(UUID eventId, String skipped) implements Outcome {}

    /**
     * The transaction failed; the attempt was recorded.
     *
     * @param exhausted no attempt left: the event stays stored and needs a person
     * @param clinic the clinic failed, not the event: no attempt was spent
     */
    record Failed(
        UUID eventId, String code, boolean exhausted, boolean clinic, RuntimeException cause)
        implements Outcome {}
  }

  /** Reserves, decides and applies the next due event of the channel's clinic. */
  public Outcome processNext(ChannelAccess access, InboundHandler handler) {
    AtomicReference<StoredEvent> reserved = new AtomicReference<>();
    Instant at = now();
    try {
      return persistence.inTransaction(
          ClinicTenant.ofChannel(access),
          session -> {
            StoredEvent event = reserve(session, at);
            if (event == null) return new Outcome.Empty();
            reserved.set(event);
            InboundAction action = handler.decide(event);
            String skipped = null;
            if (action instanceof InboundAction.Store store) {
              apply(session, access, store.message(), at);
            } else if (action instanceof InboundAction.Status status) {
              skipped = applyStatus(session, status);
            } else if (action instanceof InboundAction.Skip skip) {
              skipped = skip.code();
            }
            session
                .createNativeMutationQuery(
                    "UPDATE whatsapp_inbound_events SET processed_at = :at, last_error = :code,"
                        + " next_attempt_at = NULL WHERE id = :id")
                .setParameter("at", utc(at))
                .setParameter("code", skipped, String.class)
                .setParameter("id", event.id())
                .executeUpdate();
            return new Outcome.Processed(event.id(), skipped);
          });
    } catch (RuntimeException failure) {
      StoredEvent event = reserved.get();
      if (event == null) throw failure;
      return recordFailure(access, event.id(), failure);
    }
  }

  /**
   * True while the clinic has events not yet processed that may still be tried, or media still to
   * download (CRM-084b1), due now or later.
   */
  public boolean hasRetryable(ChannelAccess access, boolean includeMedia) {
    return persistence.inTransaction(
        ClinicTenant.ofChannel(access),
        session ->
            !session
                .createNativeQuery(
                    "SELECT 1 FROM whatsapp_inbound_events"
                        + " WHERE processed_at IS NULL AND attempts < :max"
                        + (includeMedia
                            ? " UNION ALL SELECT 1 FROM whatsapp_media WHERE status = 'PENDING'"
                            : "")
                        + " LIMIT 1",
                    Integer.class)
                .setParameter("max", MAX_ATTEMPTS)
                .getResultList()
                .isEmpty());
  }

  /**
   * Clears the raw JSON of events processed before {@code cutoff} (ADR-036: 30 days, LGPD). The
   * rows stay for deduplication.
   *
   * @return how many payloads were cleared
   */
  public int clearPayloads(ChannelAccess access, Instant cutoff) {
    int total = 0;
    int cleared;
    // In batches, each in its own short transaction: the first run on a large clinic must not hit
    // the statement timeout.
    do {
      cleared =
          persistence.inTransaction(
              ClinicTenant.ofChannel(access),
              session ->
                  session
                      .createNativeMutationQuery(
                          """
                          UPDATE whatsapp_inbound_events SET payload = NULL
                          WHERE id IN (SELECT id FROM whatsapp_inbound_events
                                       WHERE payload IS NOT NULL AND processed_at < :cutoff
                                       LIMIT :batch)
                          """)
                      .setParameter("cutoff", utc(cutoff))
                      .setParameter("batch", RETENTION_BATCH)
                      .executeUpdate());
      total += cleared;
    } while (cleared == RETENTION_BATCH);
    return total;
  }

  /**
   * The contact of a message event, from its payload: the sender, or the recipient of an echo. The
   * last 8 digits match the number with and without the Brazilian ninth digit; two numbers sharing
   * them only wait for each other.
   */
  private static String contactKey(String alias) {
    return ("right(regexp_replace(COALESCE(CASE WHEN %1$s.field = 'smb_message_echoes'"
            + " THEN %1$s.payload #>> '{message,to}' ELSE %1$s.payload #>> '{message,from}' END,"
            + " ''), '[^0-9]', '', 'g'), 8)")
        .formatted(alias);
  }

  private static StoredEvent reserve(Session session, Instant at) {
    List<Object[]> rows =
        session
            .createNativeQuery(
                """
                SELECT e.id, e.kind, e.field, e.event_at, e.received_at, CAST(e.payload AS text)
                FROM whatsapp_inbound_events e
                WHERE e.processed_at IS NULL AND e.attempts < :max
                  AND (e.next_attempt_at IS NULL OR e.next_attempt_at <= :at)
                  AND NOT EXISTS (
                      SELECT 1 FROM whatsapp_inbound_events p
                      WHERE e.kind = 'MESSAGE' AND p.kind = 'MESSAGE'
                        AND p.processed_at IS NULL AND p.attempts < :max AND p.id <> e.id
                        AND %s = %s
                        AND (p.event_at, p.received_at, p.id) < (e.event_at, e.received_at, e.id))
                ORDER BY e.event_at NULLS FIRST, e.received_at, e.id
                LIMIT 1
                FOR UPDATE OF e SKIP LOCKED
                """
                    .formatted(contactKey("p"), contactKey("e")),
                Object[].class)
            .setParameter("max", MAX_ATTEMPTS)
            .setParameter("at", utc(at))
            .getResultList();
    if (rows.isEmpty()) return null;
    Object[] row = rows.getFirst();
    return new StoredEvent(
        (UUID) row[0],
        InboundKind.valueOf((String) row[1]),
        (String) row[2],
        instant(row[3]),
        instant(row[4]),
        (String) row[5]);
  }

  /**
   * Contact (created without an author or reused), lead by RN-01 with the detected origin or a
   * touch, conversation and message. Echoes of the WhatsApp Business app never open a lead.
   */
  private void apply(Session session, ChannelAccess access, InboundMessage message, Instant at) {
    String phone = message.contact().phone().e164();
    // One contact at a time, across workers and webhook-triggered runs.
    session
        .createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:key))::text", String.class)
        .setParameter("key", "whatsapp-contact:" + phone)
        .getSingleResult();
    // A message already stored (an event reprocessed by hand) changes nothing, not even the lead.
    boolean stored =
        !session
            .createNativeQuery(
                "SELECT 1 FROM whatsapp_messages WHERE wamid = :wamid", Integer.class)
            .setParameter("wamid", message.wamid())
            .getResultList()
            .isEmpty();
    if (stored) return;
    UUID contact =
        switch (contacts.createFromWhatsapp(session, message.contact(), at)) {
          case ContactCreation.Created created -> created.id();
          case ContactCreation.Existing existing -> existing.id();
        };
    if (!message.fromBusinessApp()) {
      try {
        leads.openWithin(session, contact, message.origin(), at);
      } catch (LeadException refused) {
        // ALREADY_OPEN: an arrival with a detected origin becomes a touch (RN-02).
        // CONTACT_UNAVAILABLE: archived contacts keep the message but get no lead.
        if (refused.reason() == LeadException.Reason.ALREADY_OPEN
            && message.origin().source() != LeadOrigin.Source.NONE) {
          leads.recordTouchWithin(session, contact, message.origin(), at);
        }
      }
    }
    UUID conversation =
        session
            .createNativeQuery(
                """
                INSERT INTO whatsapp_conversations (id, contact_id, channel_id, created_at,
                                                    last_message_at, last_inbound_at)
                VALUES (:id, :contact, :channel, :at, :sentAt, :inboundAt)
                ON CONFLICT (contact_id) DO UPDATE SET
                    channel_id = EXCLUDED.channel_id,
                    last_message_at = GREATEST(whatsapp_conversations.last_message_at,
                                               EXCLUDED.last_message_at),
                    last_inbound_at = GREATEST(whatsapp_conversations.last_inbound_at,
                                               EXCLUDED.last_inbound_at)
                RETURNING id
                """,
                UUID.class)
            .setParameter("id", UUID.randomUUID())
            .setParameter("contact", contact)
            .setParameter("channel", access.channelId())
            .setParameter("at", utc(at))
            .setParameter("sentAt", utc(message.sentAt()))
            .setParameter(
                "inboundAt",
                message.fromBusinessApp() ? null : utc(message.sentAt()),
                OffsetDateTime.class)
            .getSingleResult();
    UUID messageId = UUID.randomUUID();
    int inserted =
        session
            .createNativeMutationQuery(
                """
            INSERT INTO whatsapp_messages (id, conversation_id, wamid, direction, sent_by_app,
                                           message_type, body, media_id, media_mime, sent_at,
                                           created_at)
            VALUES (:id, :conversation, :wamid, :direction, :app, :type, :body, :mediaId,
                    :mediaMime, :sentAt, :at)
            ON CONFLICT (wamid) DO NOTHING
            """)
            .setParameter("id", messageId)
            .setParameter("conversation", conversation)
            .setParameter("wamid", message.wamid())
            .setParameter("direction", message.fromBusinessApp() ? "OUT" : "IN")
            .setParameter("app", message.fromBusinessApp())
            .setParameter("type", message.type())
            .setParameter("body", message.body(), String.class)
            .setParameter("mediaId", message.mediaId(), String.class)
            .setParameter("mediaMime", message.mediaMime(), String.class)
            .setParameter("sentAt", utc(message.sentAt()))
            .setParameter("at", utc(at))
            .executeUpdate();
    // The file is fetched later by the media step (CRM-084b1), never inside this transaction.
    if (inserted == 1 && message.mediaId() != null) {
      session
          .createNativeMutationQuery(
              "INSERT INTO whatsapp_media (id, message_id, meta_media_id, next_attempt_at,"
                  + " created_at, updated_at) VALUES (:id, :message, :media, :at, :at, :at)")
          .setParameter("id", UUID.randomUUID())
          .setParameter("message", messageId)
          .setParameter("media", message.mediaId())
          .setParameter("at", utc(at))
          .executeUpdate();
    }
  }

  /** Order of the delivery statuses: a status never moves a reply back. */
  private static int rank(String status) {
    return switch (status) {
      case "SENT" -> 1;
      case "DELIVERED" -> 2;
      case "READ" -> 3;
      default -> 0;
    };
  }

  /**
   * Reconciles a reply with its delivery status (CRM-084a): found by the outbox id Meta echoes
   * back, or by the wamid. An UNCERTAIN reply that Meta reports as sent becomes SENT and its
   * message joins the conversation. The outbox row exists before the send and Meta echoes its id,
   * so a status of a CRM reply always finds it; any other (a message sent from the WhatsApp
   * Business app, before this release or by another integration) is skipped as untracked.
   *
   * @return a skip code, or null when applied
   */
  private static String applyStatus(Session session, InboundAction.Status status) {
    List<Object[]> rows =
        session
            .createNativeQuery(
                "SELECT id, status, conversation_id, body FROM whatsapp_outbox"
                    + " WHERE id = :outbox OR wamid = :wamid ORDER BY (id = :outbox) DESC LIMIT 1"
                    + " FOR UPDATE",
                Object[].class)
            .setParameter("outbox", status.outboxId(), UUID.class)
            .setParameter("wamid", status.wamid())
            .getResultList();
    if (rows.isEmpty()) return "STATUS_UNTRACKED";
    UUID id = (UUID) rows.getFirst()[0];
    String current = (String) rows.getFirst()[1];
    UUID conversation = (UUID) rows.getFirst()[2];
    String body = (String) rows.getFirst()[3];
    String next = status.status().toUpperCase(java.util.Locale.ROOT);
    boolean failed = next.equals("FAILED");
    // A status for a QUEUED reply means Meta got it although the send looked refused: it is
    // marked and never sent again.
    if (current.equals("FAILED")) return null;
    if (!failed && rank(next) <= rank(current)) return null;
    session
        .createNativeMutationQuery(
            "UPDATE whatsapp_outbox SET status = :status, wamid = COALESCE(wamid, :wamid),"
                + " sent_at = COALESCE(sent_at, :at), error_code = :code, lease_until = NULL,"
                + " updated_at = :at WHERE id = :id")
        .setParameter("status", next)
        .setParameter("wamid", status.wamid())
        .setParameter("at", utc(status.at()))
        .setParameter("code", failed ? status.errorCode() : null, String.class)
        .setParameter("id", id)
        .executeUpdate();
    if (!failed) {
      OutboxRepository.insertMessage(
          session, id, conversation, status.wamid(), body, next, status.at());
    }
    session
        .createNativeMutationQuery(
            "UPDATE whatsapp_messages SET status = :status WHERE outbox_id = :id")
        .setParameter("status", next)
        .setParameter("id", id)
        .executeUpdate();
    return null;
  }

  private Outcome recordFailure(ChannelAccess access, UUID eventId, RuntimeException failure) {
    String code = code(failure);
    Instant at = now();
    boolean clinic = clinicFailure(failure);
    Integer attempts;
    try {
      attempts =
          persistence.inTransaction(
              ClinicTenant.ofChannel(access),
              session -> {
                Integer previous =
                    session
                        .createNativeQuery(
                            "SELECT attempts FROM whatsapp_inbound_events WHERE id = :id"
                                + " AND processed_at IS NULL FOR UPDATE",
                            Integer.class)
                        .setParameter("id", eventId)
                        .getResultList()
                        .stream()
                        .findFirst()
                        .orElse(null);
                // Processed meanwhile by another worker: nothing to record.
                if (previous == null) return null;
                int next = clinic ? previous : previous + 1;
                session
                    .createNativeMutationQuery(
                        "UPDATE whatsapp_inbound_events SET attempts = :attempts,"
                            + " next_attempt_at = :next, last_error = :code"
                            + " WHERE id = :id AND processed_at IS NULL")
                    .setParameter("attempts", next)
                    .setParameter(
                        "next",
                        next >= MAX_ATTEMPTS
                            ? null
                            : utc(at.plus(clinic ? FIRST_DELAY : delay(next))),
                        OffsetDateTime.class)
                    .setParameter("code", code)
                    .setParameter("id", eventId)
                    .executeUpdate();
                return next;
              });
    } catch (RuntimeException unreachable) {
      // The clinic is down: the event is untouched and the sweep comes back to it.
      return new Outcome.Failed(eventId, code, false, true, failure);
    }
    if (attempts == null) return new Outcome.Processed(eventId, null);
    return new Outcome.Failed(eventId, code, attempts >= MAX_ATTEMPTS, clinic, failure);
  }

  /**
   * The clinic itself failed, not the event: database unavailable, migration pending, identity
   * mismatch, missing secret or access refused. Retried without spending attempts.
   */
  static boolean clinicFailure(RuntimeException failure) {
    if (failure instanceof TenantAccessException || failure instanceof TenantSecretException) {
      return true;
    }
    return failure instanceof ClinicDataException data
        && (data.reason() == ClinicDataException.Reason.UNAVAILABLE
            || data.reason() == ClinicDataException.Reason.SCHEMA_OUTDATED
            || data.reason() == ClinicDataException.Reason.IDENTITY_MISMATCH);
  }

  /** 30 s, 1 min, 2 min... capped at one hour. */
  public static Duration delay(int attempts) {
    Duration delay = FIRST_DELAY.multipliedBy(1L << Math.min(attempts - 1, 20));
    return delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay;
  }

  /** Stable code only: never a message, SQL text or value. */
  private static String code(RuntimeException failure) {
    String code =
        failure instanceof ClinicDataException data
            ? data.getMessage()
            : failure.getClass().getSimpleName();
    if (code == null || code.isBlank()) code = "UNKNOWN";
    return code.length() > 64 ? code.substring(0, 64) : code;
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
