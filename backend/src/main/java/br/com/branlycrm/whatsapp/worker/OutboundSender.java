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
import br.com.branlycrm.whatsapp.storage.MediaStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 *   <li>A file (CRM-084b2) is first read from the bucket and uploaded to Meta, once: Meta's media
 *       id is kept for the retries. An upload that fails is not a send, so it is retried like a
 *       send that never reached Meta.
 * </ul>
 *
 * Logs carry ids and codes only, never text or numbers.
 */
@Component
@ConditionalOnProperty(name = "app.whatsapp.worker.enabled", havingValue = "true")
public class OutboundSender {
  static final int MAX_ATTEMPTS = 5;
  static final int BATCH = 50;

  /**
   * Covers the slowest reply: a file read from the bucket, uploaded to Meta (up to {@link
   * MetaGraphClient#UPLOAD_TIMEOUT}) and sent. A shorter lease could expire mid-upload and mark as
   * uncertain a reply that never left.
   */
  static final Duration LEASE = Duration.ofMinutes(6);

  private static final Logger LOG = LoggerFactory.getLogger(OutboundSender.class);

  private final TenantRegistryRepository tenants;
  private final ChannelRegistryRepository channels;
  private final OutboundChannelRepository senders;
  private final OutboxRepository outbox;
  private final ChannelUnsealer unsealer;
  private final MetaGraphClient meta;
  private final MediaStorage storage;
  private final Clock clock;

  public OutboundSender(
      TenantRegistryRepository tenants,
      ChannelRegistryRepository channels,
      OutboundChannelRepository senders,
      OutboxRepository outbox,
      ChannelUnsealer unsealer,
      MetaGraphClient meta,
      MediaStorage storage,
      ObjectProvider<Clock> clock) {
    this.tenants = tenants;
    this.channels = channels;
    this.senders = senders;
    this.outbox = outbox;
    this.unsealer = unsealer;
    this.meta = meta;
    this.storage = storage;
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
        if (next.get().isMedia()) {
          // One file per channel per pass: the drain thread is shared with every clinic's inbox,
          // so the next pass handles received messages before another upload.
          channels.keepOutbound(channelId);
          return true;
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
      if (reply.isMedia()) {
        Upload uploaded = upload(access, channelId, phoneNumberId, token, reply);
        if (uploaded.mediaId() == null) return uploaded.continues();
        result =
            meta.sendMedia(
                token,
                phoneNumberId,
                reply.toDigits(),
                reply.media().messageType(),
                uploaded.mediaId(),
                reply.body(),
                reply.media().fileName(),
                reply.id().toString());
      } else {
        result =
            meta.sendText(
                token, phoneNumberId, reply.toDigits(), reply.body(), reply.id().toString());
      }
    } catch (IllegalArgumentException invalid) {
      // Checked before any request: nothing was sent.
      String code = reply.isMedia() ? "INVALID_MEDIA" : "INVALID_RECIPIENT";
      outbox.fail(access, reply.id(), code, now);
      LOG.warn("WhatsApp reply failed code={} channel={} reply={}", code, channelId, reply.id());
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
        notSent(access, channelId, reply, notSent.code(), at);
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

  /** Never reached Meta: retried with a growing delay, failed after {@link #MAX_ATTEMPTS}. */
  private void notSent(
      ChannelAccess access, UUID channelId, Outbound reply, String code, Instant at) {
    if (reply.attempts() >= MAX_ATTEMPTS) {
      outbox.fail(access, reply.id(), code, at);
      LOG.warn("WhatsApp reply failed code={} channel={} reply={}", code, channelId, reply.id());
    } else {
      outbox.retry(access, reply.id(), at.plus(InboundQueueRepository.delay(reply.attempts())), at);
      LOG.info("WhatsApp reply retry code={} channel={} reply={}", code, channelId, reply.id());
    }
  }

  /**
   * The result of {@link #upload}: Meta's media id, or null when the reply was already retried or
   * failed, with whether the channel's batch goes on.
   */
  private record Upload(String mediaId, boolean continues) {}

  /**
   * Meta's media id of the reply's file: the one kept from an earlier attempt, or a new upload
   * (CRM-084b2).
   */
  private Upload upload(
      ChannelAccess access, UUID channelId, String phoneNumberId, String token, Outbound reply) {
    OutboxRepository.OutboundMedia media = reply.media();
    if (media.metaMediaId() != null) return new Upload(media.metaMediaId(), true);
    Path file = null;
    MetaSendResult result;
    try {
      file = Files.createTempFile("whatsapp-send-", ".bin");
      storage.get(media.storageKey(), file);
      result =
          meta.uploadMedia(
              token,
              phoneNumberId,
              file,
              media.contentType(),
              media.fileName() == null
                  ? reply.id() + "." + extension(media.contentType())
                  : media.fileName());
    } catch (MediaStorage.StorageException | IOException unavailable) {
      // The bucket did not answer: nothing reached Meta.
      result = new MetaSendResult.NotSent("STORAGE_UNAVAILABLE");
    } finally {
      if (file != null) {
        try {
          Files.deleteIfExists(file);
        } catch (IOException ignored) {
          // A temporary file; the system cleans it.
        }
      }
    }
    Instant at = clock.instant();
    return switch (result) {
      case MetaSendResult.Sent uploaded -> {
        outbox.uploaded(access, media.id(), uploaded.wamid(), at);
        yield new Upload(uploaded.wamid(), true);
      }
      case MetaSendResult.NotSent notSent -> {
        notSent(access, channelId, reply, notSent.code(), at);
        yield new Upload(null, false);
      }
      case MetaSendResult.Refused refused -> {
        outbox.fail(access, reply.id(), refused.code(), at);
        LOG.info(
            "WhatsApp media refused code={} channel={} reply={}",
            refused.code(),
            channelId,
            reply.id());
        yield new Upload(null, true);
      }
      case MetaSendResult.TokenInvalid invalid -> {
        outbox.fail(access, reply.id(), "TOKEN_INVALID", at);
        LOG.error("WhatsApp media failed code=TOKEN_INVALID channel={}", channelId);
        yield new Upload(null, false);
      }
      case MetaSendResult.Uncertain uncertain -> {
        // Not returned by an upload; treated as never sent.
        notSent(access, channelId, reply, uncertain.code(), at);
        yield new Upload(null, false);
      }
    };
  }

  private static String extension(String contentType) {
    return java.util.Arrays.stream(br.com.branlycrm.whatsapp.domain.MediaType.values())
        .filter(type -> type.contentType().equals(contentType))
        .map(br.com.branlycrm.whatsapp.domain.MediaType::extension)
        .findFirst()
        .orElse("bin");
  }
}
