package br.com.branlycrm.whatsapp.services;

import br.com.branlycrm.tenant.domain.ChannelAccess;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.repositories.TenantRegistryRepository;
import br.com.branlycrm.whatsapp.repositories.ChannelRegistryRepository;
import br.com.branlycrm.whatsapp.repositories.InboundQueueRepository;
import br.com.branlycrm.whatsapp.repositories.InboundQueueRepository.Outcome;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drains the inbound events of one channel's clinic (CRM-082b, ADR-036). Runs only in the worker;
 * the clinic comes from the central registry ({@code openForChannel}), never from the payload. Logs
 * carry channel and event ids and stable codes, never content or phones.
 */
@Service
public class InboundProcessingService {
  /** Events per pass, so one busy clinic never holds the others back. */
  static final int BATCH = 200;

  /** ADR-036: the raw JSON is cleared 30 days after processing (LGPD). */
  static final Duration RETENTION = Duration.ofDays(30);

  private static final Logger LOG = LoggerFactory.getLogger(InboundProcessingService.class);

  private final ChannelRegistryRepository channels;
  private final TenantRegistryRepository tenants;
  private final InboundQueueRepository queue;
  private final InboundEventInterpreter interpreter;
  private final br.com.branlycrm.whatsapp.storage.MediaStorage storage;
  private final Clock clock;

  public InboundProcessingService(
      ChannelRegistryRepository channels,
      TenantRegistryRepository tenants,
      InboundQueueRepository queue,
      ObjectProvider<JsonMapper> json,
      ObjectProvider<Clock> clock,
      br.com.branlycrm.whatsapp.storage.MediaStorage storage) {
    this.storage = storage;
    this.channels = channels;
    this.tenants = tenants;
    this.queue = queue;
    this.interpreter =
        new InboundEventInterpreter(json.getIfAvailable(() -> JsonMapper.builder().build()));
    this.clock = clock.getIfAvailable(Clock::systemUTC);
  }

  /** Result of one pass over a channel. */
  public record Pass(int processed, int failed, boolean more) {}

  /**
   * Processes up to {@link #BATCH} due events, then clears the pending signal when nothing is left
   * to try. A channel no longer active, or a suspended clinic, is left untouched.
   */
  public Pass drain(UUID channelId) {
    Optional<ChannelAccess> found = tenants.findChannelAccess(channelId);
    if (found.isEmpty()) {
      LOG.info("WhatsApp inbound skipped reason=CHANNEL_INACTIVE channel={}", channelId);
      return new Pass(0, 0, false);
    }
    ChannelAccess access = found.get();
    int processed = 0;
    int failed = 0;
    try {
      while (processed + failed < BATCH) {
        Outcome outcome = queue.processNext(access, interpreter);
        if (outcome instanceof Outcome.Empty) break;
        if (outcome instanceof Outcome.Processed done) {
          processed++;
          if (done.skipped() != null && done.skipped().startsWith("INVALID_")) {
            // A message that can never be applied: visible, never silent.
            LOG.warn(
                "WhatsApp inbound skipped reason={} channel={} event={}",
                done.skipped(),
                channelId,
                done.eventId());
          } else if (done.skipped() != null) {
            LOG.debug(
                "WhatsApp inbound skipped reason={} channel={} event={}",
                done.skipped(),
                channelId,
                done.eventId());
          }
        } else if (outcome instanceof Outcome.Failed failure) {
          failed++;
          if (failure.exhausted()) {
            LOG.error(
                "WhatsApp inbound failed permanently code={} channel={} event={}",
                failure.code(),
                channelId,
                failure.eventId());
          } else {
            LOG.warn(
                "WhatsApp inbound retry code={} clinic={} channel={} event={}",
                failure.code(),
                failure.clinic(),
                channelId,
                failure.eventId());
          }
          // A failing clinic (database down, schema outdated) is retried later, not hammered. A
          // failing event waits for its delay; the other contacts go on.
          if (failure.clinic()) break;
        }
      }
      boolean more = processed + failed >= BATCH;
      if (!more) settle(channelId, access);
      return new Pass(processed, failed, more);
    } catch (TenantAccessException deactivated) {
      LOG.info("WhatsApp inbound stopped reason=CHANNEL_INACTIVE channel={}", channelId);
      return new Pass(processed, failed, false);
    }
  }

  /**
   * Clears the signal, then looks again: an event the webhook stored meanwhile, or one waiting for
   * a retry, raises it back, so the sweep never forgets a clinic with work.
   */
  private void settle(UUID channelId, ChannelAccess access) {
    channels.clearPending(channelId);
    // Pending media keep the clinic in the sweep only when they can be stored (CRM-084b1).
    if (queue.hasRetryable(access, storage.configured())) channels.keepPending(channelId);
  }

  /** Clears raw payloads older than the retention in every active channel's clinic. */
  public int applyRetention() {
    int cleared = 0;
    for (UUID channelId : channels.activeChannels()) {
      try {
        Optional<ChannelAccess> access = tenants.findChannelAccess(channelId);
        if (access.isPresent()) {
          cleared += queue.clearPayloads(access.get(), clock.instant().minus(RETENTION));
        }
      } catch (RuntimeException failure) {
        LOG.warn(
            "WhatsApp retention failed channel={} code={}",
            channelId,
            failure.getClass().getSimpleName());
      }
    }
    if (cleared > 0) LOG.info("WhatsApp retention cleared={}", cleared);
    return cleared;
  }
}
