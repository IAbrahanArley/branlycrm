package br.com.branlycrm.whatsapp.repositories;

import br.com.branlycrm.tenant.domain.ChannelAccess;
import br.com.branlycrm.tenant.persistence.ClinicPersistence;
import br.com.branlycrm.tenant.persistence.ClinicTenant;
import br.com.branlycrm.whatsapp.domain.InboundEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;

/**
 * Webhook writes to {@code whatsapp_inbound_events} in the channel's clinic database (ADR-036). The
 * webhook only inserts: reading and processing belong to the worker.
 */
@Repository
public class InboundEventRepository {
  private final ClinicPersistence persistence;
  private final Clock clock;

  public InboundEventRepository(ClinicPersistence persistence, ObjectProvider<Clock> clock) {
    this.persistence = persistence;
    this.clock = clock.getIfAvailable(Clock::systemUTC);
  }

  /**
   * Stores the events in one transaction, skipping the ones already stored.
   *
   * @return how many were new
   */
  public int insert(ChannelAccess access, List<InboundEvent> events) {
    if (events.isEmpty()) return 0;
    OffsetDateTime receivedAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    return persistence.inTransaction(
        ClinicTenant.ofChannel(access),
        session -> {
          int inserted = 0;
          for (InboundEvent event : events) {
            inserted +=
                session
                    .createNativeMutationQuery(
                        "INSERT INTO whatsapp_inbound_events (id, dedup_key, kind, field,"
                            + " channel_id, wamid, event_at, payload, received_at)"
                            + " VALUES (:id, :dedup, :kind, :field, :channel, :wamid, :eventAt,"
                            + " CAST(:payload AS jsonb), :receivedAt)"
                            + " ON CONFLICT (dedup_key) DO NOTHING")
                    .setParameter("id", UUID.randomUUID())
                    .setParameter("dedup", event.dedupKey())
                    .setParameter("kind", event.kind().name())
                    .setParameter("field", event.field())
                    .setParameter("channel", access.channelId())
                    .setParameter("wamid", event.wamid(), String.class)
                    .setParameter("eventAt", utc(event.eventAt()), OffsetDateTime.class)
                    .setParameter("payload", event.payload())
                    .setParameter("receivedAt", receivedAt)
                    .executeUpdate();
          }
          return inserted;
        });
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
