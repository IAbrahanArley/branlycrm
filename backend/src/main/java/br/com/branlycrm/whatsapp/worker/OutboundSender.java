package br.com.branlycrm.whatsapp.worker;

import br.com.branlycrm.tenant.domain.ChannelAccess;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.repositories.TenantRegistryRepository;
import br.com.branlycrm.whatsapp.meta.MetaGraphClient;
import br.com.branlycrm.whatsapp.meta.MetaSendResult;
import br.com.branlycrm.whatsapp.repositories.ChannelRegistryRepository;
import br.com.branlycrm.whatsapp.repositories.InboundQueueRepository;
import br.com.branlycrm.whatsapp.repositories.OutboundChannelRepository;
import br.com.branlycrm.whatsapp.repositories.OutboxRepository;
import br.com.branlycrm.whatsapp.repositories.OutboxRepository.Outbound;
import br.com.branlycrm.whatsapp.secrets.ChannelSecretException;
import br.com.branlycrm.whatsapp.secrets.ChannelSecretPurpose;
import br.com.branlycrm.whatsapp.secrets.ChannelUnsealer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Sends the queued replies of one channel's clinic (CRM-084a, ADR-036, ADR-005).
 *
 * <ul>
 *   <li>Claimed one at a time (SENDING, with a lease); the 24-hour window is checked again.
 *   <li>Accepted: SENT and the message joins the conversation.
 *   <li>Never reached Meta (connection refused, rate limit): retried with a growing delay, up to
 *       {@link #MAX_ATTEMPTS}.
 *   <li>May have reached Meta (timeout, connection lost, server error): UNCERTAIN, never resent; a
 *       delivery status carrying the outbox id reconciles it.
 *   <li>Refused or invalid token: FAILED with a stable code.
 * </ul>
 *
 * Logs carry ids and codes only, never text or numbers.
 */
@Component
@ConditionalOnProperty(name = "app.whatsapp.worker.enabled", havingValue = "true")
public class OutboundSender {
  static final int MAX_ATTEMPTS = 5;
  static final int BATCH = 50;
  static final Duration LEASE = Duration.ofMinutes(2);
  private static final Logger LOG = LoggerFactory.getLogger(OutboundSender.class);

  private final TenantRegistryRepository tenants;
  private final ChannelRegistryRepository channels;
  private final OutboundChannelRepository senders;
  private final OutboxRepository outbox;
  private final ChannelUnsealer unsealer;
  private final MetaGraphClient meta;
  private final Clock clock;

  public OutboundSender(
      TenantRegistryRepository tenants,
      ChannelRegistryRepository channels,
      OutboundChannelRepository senders,
      OutboxRepository outbox,
      ChannelUnsealer unsealer,
      MetaGraphClient meta,
      ObjectProvider<Clock> clock) {
    this.tenants = tenants;
    this.channels = channels;
    this.senders = senders;
    this.outbox = outbox;
    this.unsealer = unsealer;
    this.meta = meta;
    this.clock = clock.getIfAvailable(Clock::systemUTC);
  }

  /**
   * Sends up to {@link #BATCH} due replies, then clears the outbound signal when nothing is left to
   * send.
   *
   * @return true when a full batch ran and more may be due
   */
  public boolean drain(UUID channelId) {
    Optional<ChannelAccess> found = tenants.findChannelAccess(channelId);
    Optional<OutboundChannelRepository.Sender> sender = senders.sender(channelId);
    if (found.isEmpty() || sender.isEmpty()) {
      LOG.info("WhatsApp outbound skipped reason=CHANNEL_INACTIVE channel={}", channelId);
      return false;
    }
    ChannelAccess access = found.get();
    int sent = 0;
    try {
      int expired = outbox.expireLeases(access, clock.instant());
      if (expired > 0) {
        LOG.warn(
            "WhatsApp outbound uncertain after restart channel={} replies={}", channelId, expired);
      }
      String token;
      try {
        token = unsealer.open(channelId, ChannelSecretPurpose.ACCESS_TOKEN, sender.get().token());
      } catch (ChannelSecretException unreadable) {
        LOG.error("WhatsApp outbound token unreadable channel={}", channelId);
        return false;
      }
      while (sent < BATCH) {
        Instant now = clock.instant();
        Optional<Outbound> next = outbox.claim(access, now, LEASE);
        if (next.isEmpty()) break;
        sent++;
        if (!send(access, channelId, sender.get().phoneNumberId(), token, next.get())) {
          // Meta unreachable or the token refused: the rest waits for the next sweep instead of
          // turning, one by one, uncertain or failed.
          channels.keepOutbound(channelId);
          return false;
        }
      }
      boolean more = sent >= BATCH;
      if (!more) {
        channels.clearOutbound(channelId);
        if (outbox.hasPending(access)) channels.keepOutbound(channelId);
      }
      return more;
    } catch (TenantAccessException deactivated) {
      LOG.info("WhatsApp outbound stopped reason=CHANNEL_INACTIVE channel={}", channelId);
      return false;
    }
  }

  /** Returns false when the channel's batch should stop (network trouble or invalid token). */
  private boolean send(
      ChannelAccess access, UUID channelId, String phoneNumberId, String token, Outbound reply) {
    Instant now = clock.instant();
    if (reply.lastInboundAt() == null
        || reply.lastInboundAt().isBefore(now.minus(OutboxRepository.WINDOW))) {
      outbox.fail(access, reply.id(), "WINDOW_CLOSED", now);
      LOG.info(
          "WhatsApp reply refused code=WINDOW_CLOSED channel={} reply={}", channelId, reply.id());
      return true;
    }
    MetaSendResult result;
    try {
      result =
          meta.sendText(
              token, phoneNumberId, reply.toDigits(), reply.body(), reply.id().toString());
    } catch (IllegalArgumentException invalid) {
      // Checked before any request: nothing was sent.
      outbox.fail(access, reply.id(), "INVALID_RECIPIENT", now);
      LOG.warn(
          "WhatsApp reply failed code=INVALID_RECIPIENT channel={} reply={}",
          channelId,
          reply.id());
      return true;
    }
    Instant at = clock.instant();
    return switch (result) {
      case MetaSendResult.Sent accepted -> {
        outbox.sent(access, reply, accepted.wamid(), at);
        LOG.info("WhatsApp reply sent channel={} reply={}", channelId, reply.id());
        yield true;
      }
      case MetaSendResult.NotSent notSent -> {
        if (reply.attempts() >= MAX_ATTEMPTS) {
          outbox.fail(access, reply.id(), notSent.code(), at);
          LOG.warn(
              "WhatsApp reply failed code={} channel={} reply={}",
              notSent.code(),
              channelId,
              reply.id());
        } else {
          outbox.retry(
              access, reply.id(), at.plus(InboundQueueRepository.delay(reply.attempts())), at);
          LOG.info(
              "WhatsApp reply retry code={} channel={} reply={}",
              notSent.code(),
              channelId,
              reply.id());
        }
        yield false;
      }
      case MetaSendResult.Uncertain uncertain -> {
        outbox.uncertain(access, reply.id(), at);
        LOG.warn(
            "WhatsApp reply uncertain code={} channel={} reply={}",
            uncertain.code(),
            channelId,
            reply.id());
        yield false;
      }
      case MetaSendResult.Refused refused -> {
        outbox.fail(access, reply.id(), refused.code(), at);
        LOG.info(
            "WhatsApp reply refused code={} channel={} reply={}",
            refused.code(),
            channelId,
            reply.id());
        yield true;
      }
      case MetaSendResult.TokenInvalid invalid -> {
        outbox.fail(access, reply.id(), "TOKEN_INVALID", at);
        LOG.error("WhatsApp reply failed code=TOKEN_INVALID channel={}", channelId);
        yield false;
      }
    };
  }
}
