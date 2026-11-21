package br.com.branlycrm.whatsapp.services;

import br.com.branlycrm.tenant.domain.ChannelAccess;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.repositories.TenantRegistryRepository;
import br.com.branlycrm.whatsapp.domain.InboundEvent;
import br.com.branlycrm.whatsapp.domain.LiveChannel;
import br.com.branlycrm.whatsapp.domain.RoutedEvent;
import br.com.branlycrm.whatsapp.domain.WebhookRoute;
import br.com.branlycrm.whatsapp.domain.WebhookSignature;
import br.com.branlycrm.whatsapp.exceptions.WebhookException;
import br.com.branlycrm.whatsapp.exceptions.WebhookException.Reason;
import br.com.branlycrm.whatsapp.repositories.ChannelRegistryRepository;
import br.com.branlycrm.whatsapp.repositories.InboundEventRepository;
import br.com.branlycrm.whatsapp.secrets.WebhookSecrets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * WhatsApp Cloud API webhook (CRM-082a, ADR-036): checks the signature before reading the body,
 * routes each event to its clinic through the central channel registry and stores it there before
 * answering. Processing belongs to the worker (CRM-082b).
 *
 * <ul>
 *   <li>The clinic comes only from the registry ({@code openForChannel}), never from the payload.
 *   <li>Events of unknown, pending, failed or disconnected channels, or of suspended clinics, are
 *       discarded and still acknowledged: Meta would retry them for days otherwise.
 *   <li>Any storage failure fails the whole delivery, so Meta retries it; deduplication makes the
 *       retry safe for the clinics already written.
 *   <li>Logs carry counts, channel ids and stable codes only: no body, phone or message.
 * </ul>
 */
@Service
public class WhatsappWebhookService {
  private static final Logger LOG = LoggerFactory.getLogger(WhatsappWebhookService.class);
  private static final Pattern CHALLENGE = Pattern.compile("[A-Za-z0-9_-]{1,128}");

  private final WebhookSecrets secrets;
  private final ChannelRegistryRepository channels;
  private final TenantRegistryRepository tenants;
  private final InboundEventRepository events;
  private final WebhookPayloadParser parser;
  private final Semaphore permits;

  public WhatsappWebhookService(
      WebhookSecrets secrets,
      ChannelRegistryRepository channels,
      TenantRegistryRepository tenants,
      InboundEventRepository events,
      ObjectProvider<JsonMapper> json,
      @Value("${app.whatsapp.webhook.max-concurrent:8}") int maxConcurrent) {
    if (maxConcurrent < 1) throw new IllegalArgumentException("max-concurrent must be positive");
    this.secrets = secrets;
    this.channels = channels;
    this.tenants = tenants;
    this.events = events;
    this.parser = new WebhookPayloadParser(json.getIfAvailable(() -> JsonMapper.builder().build()));
    this.permits = new Semaphore(maxConcurrent, true);
  }

  /**
   * The verification handshake: returns the challenge when mode and token match.
   *
   * @throws WebhookException NOT_CONFIGURED or VERIFICATION_FAILED
   */
  public String verify(String mode, String token, String challenge) {
    String expected = secrets.verifyToken().orElseThrow(() -> notConfigured("verify-token"));
    boolean tokenMatches = WebhookSignature.sameText(expected, token);
    if (!"subscribe".equals(mode)
        || !tokenMatches
        || challenge == null
        || !CHALLENGE.matcher(challenge).matches()) {
      LOG.warn("WhatsApp webhook verification refused");
      throw new WebhookException(Reason.VERIFICATION_FAILED);
    }
    return challenge;
  }

  /**
   * Checks, routes and stores one delivery.
   *
   * @throws WebhookException NOT_CONFIGURED, SIGNATURE_INVALID, MALFORMED, BUSY or STORAGE_FAILED
   */
  public Delivery receive(byte[] body, String signature) {
    byte[] secret = secrets.appSecret().orElseThrow(() -> notConfigured("app-secret"));
    try {
      if (!WebhookSignature.matches(secret, body, signature)) {
        LOG.warn("WhatsApp webhook signature refused");
        throw new WebhookException(Reason.SIGNATURE_INVALID);
      }
    } finally {
      Arrays.fill(secret, (byte) 0);
    }
    WebhookPayloadParser.Parsed parsed = parser.parse(body);
    acquire();
    long started = System.nanoTime();
    try {
      Delivery delivery = store(parsed);
      LOG.info(
          "WhatsApp webhook stored={} duplicates={} discarded={} skipped={} channels={} ms={}",
          delivery.stored(),
          delivery.duplicates(),
          delivery.discarded(),
          parsed.skipped(),
          delivery.channels(),
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
      return delivery;
    } finally {
      permits.release();
    }
  }

  private Delivery store(WebhookPayloadParser.Parsed parsed) {
    Map<UUID, List<InboundEvent>> byChannel = new LinkedHashMap<>();
    Map<String, Optional<LiveChannel>> numbers = new HashMap<>();
    Map<String, List<LiveChannel>> accounts = new HashMap<>();
    int discarded = 0;
    for (RoutedEvent routed : parsed.events()) {
      List<LiveChannel> targets;
      try {
        targets = targets(routed.route(), numbers, accounts);
      } catch (RuntimeException failure) {
        throw storageFailed(null, failure);
      }
      if (targets.isEmpty()) {
        discarded++;
        continue;
      }
      for (LiveChannel channel : targets) {
        byChannel.computeIfAbsent(channel.id(), id -> new ArrayList<>()).add(routed.event());
      }
    }
    int stored = 0;
    int duplicates = 0;
    int written = 0;
    for (Map.Entry<UUID, List<InboundEvent>> entry : byChannel.entrySet()) {
      UUID channelId = entry.getKey();
      List<InboundEvent> batch = entry.getValue();
      Optional<ChannelAccess> access;
      try {
        access = tenants.findChannelAccess(channelId);
      } catch (RuntimeException failure) {
        throw storageFailed(channelId, failure);
      }
      if (access.isEmpty()) {
        discard("CLINIC_INACTIVE", channelId, batch.size());
        discarded += batch.size();
        continue;
      }
      int inserted;
      try {
        inserted = events.insert(access.get(), batch);
        // After the clinic commit; a failure here fails the delivery so the retry signals again.
        channels.signalPending(channelId);
      } catch (TenantAccessException deactivated) {
        // Disconnected or suspended between the lookup and the connection.
        discard("CHANNEL_INACTIVE", channelId, batch.size());
        discarded += batch.size();
        continue;
      } catch (RuntimeException failure) {
        throw storageFailed(channelId, failure);
      }
      stored += inserted;
      duplicates += batch.size() - inserted;
      written++;
    }
    return new Delivery(stored, duplicates, discarded, written);
  }

  /** Active channels that should receive the event; an empty list discards it. */
  private List<LiveChannel> targets(
      WebhookRoute route,
      Map<String, Optional<LiveChannel>> numbers,
      Map<String, List<LiveChannel>> accounts) {
    if (route.byNumber()) {
      Optional<LiveChannel> channel =
          numbers.computeIfAbsent(route.phoneNumberId(), channels::findLiveByNumber);
      if (channel.isEmpty()) {
        discard("UNKNOWN_NUMBER", null, 1);
        return List.of();
      }
      LiveChannel live = channel.get();
      if (!live.active()) {
        discard("CHANNEL_INACTIVE", live.id(), 1);
        return List.of();
      }
      // The number must belong to the account of the entry.
      if (!route.wabaId().equals(live.wabaId())) {
        discard("ACCOUNT_MISMATCH", live.id(), 1);
        return List.of();
      }
      return List.of(live);
    }
    List<LiveChannel> active =
        accounts.computeIfAbsent(route.wabaId(), channels::findLiveByAccount).stream()
            .filter(LiveChannel::active)
            .toList();
    if (active.isEmpty()) {
      discard("UNKNOWN_ACCOUNT", null, 1);
      return List.of();
    }
    if (active.size() == 1) return active;
    // An account shared by clinics (an agency's): never copy one clinic's event to another. Only
    // an event that names one of the numbers goes, and only to that number's clinic.
    List<LiveChannel> named =
        active.stream().filter(c -> c.displays(route.displayPhone())).toList();
    if (named.size() != 1) {
      discard("SHARED_ACCOUNT", null, 1);
      return List.of();
    }
    return named;
  }

  private void acquire() {
    try {
      if (!permits.tryAcquire(2, TimeUnit.SECONDS)) {
        LOG.warn("WhatsApp webhook busy");
        throw new WebhookException(Reason.BUSY);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new WebhookException(Reason.BUSY);
    }
  }

  private static void discard(String reason, UUID channel, int count) {
    LOG.info("WhatsApp webhook discarded reason={} channel={} events={}", reason, channel, count);
  }

  /** Central or clinic database failure: 503, so Meta retries the whole delivery. */
  private static WebhookException storageFailed(UUID channel, RuntimeException failure) {
    LOG.error(
        "WhatsApp webhook storage failed channel={} error={}",
        channel,
        failure.getClass().getSimpleName());
    return new WebhookException(Reason.STORAGE_FAILED, failure);
  }

  private static WebhookException notConfigured(String name) {
    LOG.error("WhatsApp webhook not configured missing={}", name);
    return new WebhookException(Reason.NOT_CONFIGURED);
  }

  /**
   * Outcome of a delivery.
   *
   * @param stored new events written
   * @param duplicates events already stored by an earlier delivery
   * @param discarded events without an active channel and clinic
   * @param channels clinics written
   */
  public record Delivery(int stored, int duplicates, int discarded, int channels) {}
}
