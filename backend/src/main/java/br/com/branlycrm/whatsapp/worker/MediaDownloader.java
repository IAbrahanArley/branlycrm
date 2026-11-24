package br.com.branlycrm.whatsapp.worker;

import br.com.branlycrm.tenant.domain.ChannelAccess;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.repositories.TenantRegistryRepository;
import br.com.branlycrm.whatsapp.domain.MediaType;
import br.com.branlycrm.whatsapp.meta.MetaGraphClient;
import br.com.branlycrm.whatsapp.meta.MetaGraphException;
import br.com.branlycrm.whatsapp.repositories.InboundQueueRepository;
import br.com.branlycrm.whatsapp.repositories.MediaRepository;
import br.com.branlycrm.whatsapp.repositories.MediaRepository.Pending;
import br.com.branlycrm.whatsapp.repositories.OutboundChannelRepository;
import br.com.branlycrm.whatsapp.secrets.ChannelSecretException;
import br.com.branlycrm.whatsapp.secrets.ChannelSecretPurpose;
import br.com.branlycrm.whatsapp.secrets.ChannelUnsealer;
import br.com.branlycrm.whatsapp.storage.MediaStorage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Downloads the media of received messages (CRM-084b1, ADR-036) into the private bucket:
 *
 * <ul>
 *   <li>streamed to a temporary file, stopping past the largest accepted size;
 *   <li>the type detected from the first bytes, in an allowlist, with the size limit of its kind;
 *   <li>stored under {@code clinics/{clinic}/media/{row id}}, a key built from the database only.
 * </ul>
 *
 * Refused files stay REJECTED with the reason; outages are retried; the temporary file is always
 * deleted. Logs carry ids and codes, never content.
 */
@Component
@ConditionalOnProperty(name = "app.whatsapp.worker.enabled", havingValue = "true")
public class MediaDownloader {
  static final int BATCH = 5;
  static final Duration LEASE = Duration.ofMinutes(5);
  private static final Logger LOG = LoggerFactory.getLogger(MediaDownloader.class);

  private final TenantRegistryRepository tenants;
  private final OutboundChannelRepository senders;
  private final MediaRepository media;
  private final ChannelUnsealer unsealer;
  private final MetaGraphClient meta;
  private final MediaStorage storage;
  private final Clock clock;

  public MediaDownloader(
      TenantRegistryRepository tenants,
      OutboundChannelRepository senders,
      MediaRepository media,
      ChannelUnsealer unsealer,
      MetaGraphClient meta,
      MediaStorage storage,
      ObjectProvider<Clock> clock) {
    this.tenants = tenants;
    this.senders = senders;
    this.media = media;
    this.unsealer = unsealer;
    this.meta = meta;
    this.storage = storage;
    this.clock = clock.getIfAvailable(Clock::systemUTC);
  }

  /** Downloads up to {@link #BATCH} due media of the channel's clinic; returns how many ran. */
  public int drain(UUID channelId) {
    if (!storage.configured()) return 0;
    Optional<ChannelAccess> found = tenants.findChannelAccess(channelId);
    Optional<OutboundChannelRepository.Sender> sender = senders.sender(channelId);
    if (found.isEmpty() || sender.isEmpty()) return 0;
    ChannelAccess access = found.get();
    String token;
    try {
      token = unsealer.open(channelId, ChannelSecretPurpose.ACCESS_TOKEN, sender.get().token());
    } catch (ChannelSecretException unreadable) {
      LOG.error("WhatsApp media token unreadable channel={}", channelId);
      return 0;
    }
    int ran = 0;
    try {
      while (ran < BATCH) {
        Optional<Pending> next = media.claim(access, clock.instant(), LEASE);
        if (next.isEmpty()) break;
        ran++;
        if (!download(access, token, next.get())) break;
      }
    } catch (TenantAccessException deactivated) {
      LOG.info("WhatsApp media stopped reason=CHANNEL_INACTIVE channel={}", channelId);
    }
    return ran;
  }

  /** Returns false when the batch should stop (Meta or the storage unreachable). */
  private boolean download(ChannelAccess access, String token, Pending pending) {
    Path file = null;
    try {
      file = Files.createTempFile("branly-media-", ".bin");
      long size = meta.downloadMedia(token, pending.metaMediaId(), MediaType.MAX_BYTES, file);
      byte[] head;
      try (InputStream input = Files.newInputStream(file)) {
        head = input.readNBytes(MediaType.HEAD_BYTES);
      }
      Optional<MediaType> type = MediaType.detect(head);
      Instant at = clock.instant();
      if (type.isEmpty()) {
        media.reject(access, pending.id(), "TYPE_NOT_ALLOWED", at);
        LOG.info("WhatsApp media rejected code=TYPE_NOT_ALLOWED media={}", pending.id());
        return true;
      }
      if (size > type.get().kind().maxBytes()) {
        media.reject(access, pending.id(), "MEDIA_TOO_LARGE", at);
        LOG.info("WhatsApp media rejected code=MEDIA_TOO_LARGE media={}", pending.id());
        return true;
      }
      String key = "clinics/" + access.tenantId() + "/media/" + pending.id();
      storage.put(key, file, type.get().contentType(), size);
      media.stored(
          access, pending.id(), key, type.get().contentType(), size, sha256(file), clock.instant());
      return true;
    } catch (MetaGraphException failure) {
      Instant at = clock.instant();
      switch (failure.kind()) {
        case PERMANENT -> {
          if (failure.code().equals("MEDIA_TOO_LARGE")) {
            media.reject(access, pending.id(), "MEDIA_TOO_LARGE", at);
          } else {
            media.fail(access, pending.id(), failure.code(), at);
          }
          return true;
        }
        case TOKEN_INVALID -> {
          // The clinic may reconnect: the media waits instead of being dropped.
          retryOrFail(access, pending, "TOKEN_INVALID", at);
          return false;
        }
        default -> {
          retryOrFail(access, pending, failure.code(), at);
          return false;
        }
      }
    } catch (MediaStorage.StorageException | IOException unavailable) {
      String code =
          unavailable instanceof MediaStorage.StorageException storageFailure
              ? storageFailure.getMessage()
              : "MEDIA_TEMP_FILE";
      retryOrFail(access, pending, code, clock.instant());
      return false;
    } catch (RuntimeException unexpected) {
      // Any other failure is retried a few times, never left to loop.
      retryOrFail(access, pending, "MEDIA_INTERNAL", clock.instant());
      LOG.warn(
          "WhatsApp media error code={} media={}",
          unexpected.getClass().getSimpleName(),
          pending.id());
      return false;
    } finally {
      if (file != null) {
        try {
          Files.deleteIfExists(file);
        } catch (IOException ignored) {
          // The temporary directory is cleaned by the OS.
        }
      }
    }
  }

  private void retryOrFail(ChannelAccess access, Pending pending, String code, Instant at) {
    if (pending.attempts() >= MediaRepository.MAX_ATTEMPTS) {
      media.fail(access, pending.id(), code, at);
      LOG.warn("WhatsApp media failed code={} media={}", code, pending.id());
    } else {
      media.retry(
          access, pending.id(), at.plus(InboundQueueRepository.delay(pending.attempts())), at);
      LOG.info("WhatsApp media retry code={} media={}", code, pending.id());
    }
  }

  private static String sha256(Path file) throws IOException {
    try (InputStream input =
        new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
      input.transferTo(java.io.OutputStream.nullOutputStream());
      return HexFormat.of().formatHex(((DigestInputStream) input).getMessageDigest().digest());
    } catch (NoSuchAlgorithmException missing) {
      throw new IllegalStateException("SHA-256 unavailable", missing);
    }
  }
}
