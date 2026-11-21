package br.com.branlycrm.whatsapp.worker;

import br.com.branlycrm.whatsapp.meta.MetaGraphClient;
import br.com.branlycrm.whatsapp.meta.MetaGraphException;
import br.com.branlycrm.whatsapp.meta.MetaPhoneNumber;
import br.com.branlycrm.whatsapp.repositories.ChannelOnboardingRepository;
import br.com.branlycrm.whatsapp.repositories.ChannelOnboardingRepository.LeasedTask;
import br.com.branlycrm.whatsapp.repositories.InboundQueueRepository;
import br.com.branlycrm.whatsapp.secrets.ChannelSealer;
import br.com.branlycrm.whatsapp.secrets.ChannelSecretException;
import br.com.branlycrm.whatsapp.secrets.ChannelSecretPurpose;
import br.com.branlycrm.whatsapp.secrets.ChannelUnsealer;
import br.com.branlycrm.whatsapp.secrets.SealedSecret;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runs the channel connection tasks in the worker (CRM-081b, ADR-036): the only place that opens a
 * channel's token.
 *
 * <ol>
 *   <li><b>VERIFY_ASSETS:</b> account and number come from the token itself; the number the person
 *       chose is only a selector that must be among them. A number of another live channel fails.
 *   <li><b>REGISTER_NUMBER:</b> a two-step PIN generated here, sealed with the token, then the
 *       registration (skipped for coexistence with the WhatsApp Business app).
 *   <li><b>SUBSCRIBE_WEBHOOKS:</b> the app subscribes to the account; the channel becomes ACTIVE.
 *   <li><b>DISCONNECT:</b> unsubscribes when possible, wipes token and PIN and marks DISCONNECTED.
 * </ol>
 *
 * Meta outages are retried with a growing delay; refusals and revoked tokens fail the channel with
 * a stable code. Nothing secret reaches a log.
 */
@Component
@ConditionalOnProperty(name = "app.whatsapp.worker.enabled", havingValue = "true")
public class ChannelOnboardingProcessor {
  static final int MAX_ATTEMPTS = 6;
  static final Duration LEASE = Duration.ofMinutes(2);
  private static final Logger LOG = LoggerFactory.getLogger(ChannelOnboardingProcessor.class);

  private final ChannelOnboardingRepository tasks;
  private final ChannelUnsealer unsealer;
  private final ChannelSealer sealer;
  private final MetaGraphClient meta;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public ChannelOnboardingProcessor(
      ChannelOnboardingRepository tasks,
      ChannelUnsealer unsealer,
      ChannelSealer sealer,
      MetaGraphClient meta,
      ObjectProvider<Clock> clock) {
    this.tasks = tasks;
    this.unsealer = unsealer;
    this.sealer = sealer;
    this.meta = meta;
    this.clock = clock.getIfAvailable(Clock::systemUTC);
  }

  /** Processes the due tasks, at most {@code limit}; returns how many ran. */
  public int runPending(int limit) {
    int ran = 0;
    while (ran < limit) {
      Optional<LeasedTask> leased = tasks.lease(clock.instant(), LEASE);
      if (leased.isEmpty()) break;
      ran++;
      run(leased.get());
    }
    return ran;
  }

  private void run(LeasedTask task) {
    // A task resumed after its worker died spent an attempt; one that keeps killing the process
    // ends here.
    if (task.attempts() >= MAX_ATTEMPTS) {
      failChannel(task, "WORKER_INTERRUPTED");
      return;
    }
    try {
      switch (task.step()) {
        case "DISCONNECT" -> disconnect(task);
        case "VERIFY_ASSETS", "REGISTER_NUMBER", "SUBSCRIBE_WEBHOOKS" -> {
          if (!"PENDING".equals(task.channelStatus())) {
            tasks.drop(task, "CHANNEL_NOT_PENDING", clock.instant());
            return;
          }
          switch (task.step()) {
            case "VERIFY_ASSETS" -> verify(task);
            case "REGISTER_NUMBER" -> register(task);
            default -> subscribe(task);
          }
        }
        default -> tasks.drop(task, "UNKNOWN_STEP", clock.instant());
      }
    } catch (MetaGraphException failure) {
      switch (failure.kind()) {
        case TOKEN_INVALID -> failChannel(task, "TOKEN_INVALID");
        case PERMANENT -> failChannel(task, failure.code());
        case TRANSIENT, NOT_CONFIGURED -> retry(task, failure.code());
      }
    } catch (ChannelSecretException unsealable) {
      if (unsealable.reason() == ChannelSecretException.Reason.NOT_CONFIGURED) {
        retry(task, "KEYS_NOT_CONFIGURED");
      } else {
        failChannel(task, "TOKEN_UNREADABLE");
      }
    } catch (RuntimeException failure) {
      retry(task, "WORKER_ERROR");
    }
  }

  private void verify(LeasedTask task) {
    String token = token(task);
    List<Candidate> candidates = new ArrayList<>();
    for (String waba : meta.grantedAccounts(token)) {
      for (MetaPhoneNumber number : meta.phoneNumbers(token, waba)) {
        candidates.add(new Candidate(waba, number));
      }
    }
    Candidate chosen;
    if (task.requestedPhoneNumberId() != null) {
      chosen =
          candidates.stream()
              .filter(c -> c.number().id().equals(task.requestedPhoneNumberId()))
              .findFirst()
              .orElse(null);
      if (chosen == null) {
        failChannel(task, "NUMBER_NOT_GRANTED");
        return;
      }
    } else if (candidates.size() == 1) {
      chosen = candidates.getFirst();
    } else {
      failChannel(task, candidates.isEmpty() ? "NO_NUMBER" : "AMBIGUOUS_NUMBER");
      return;
    }
    Instant at = clock.instant();
    if (!tasks.confirmAssets(
        task.channelId(),
        chosen.waba(),
        chosen.number().id(),
        chosen.number().displayPhoneNumber(),
        at)) {
      failChannel(task, "NUMBER_IN_USE");
      return;
    }
    // Coexistence comes from the signup event and is trusted as is until the real test (CRM-074):
    // a wrong "true" skips registration, a wrong "false" makes the registration fail visibly.
    tasks.advance(task, task.coexistence() ? "SUBSCRIBE_WEBHOOKS" : "REGISTER_NUMBER", at);
    LOG.info("WhatsApp channel assets confirmed channel={}", task.channelId());
  }

  private void register(LeasedTask task) {
    String token = token(task);
    String pin;
    if (task.pin() != null) {
      pin = unsealer.open(task.channelId(), ChannelSecretPurpose.REGISTRATION_PIN, task.pin());
    } else {
      // Generated and stored before the call, so a retry registers with the same PIN. Token and
      // PIN are sealed again together with the newest key (one key version per channel).
      pin = String.format("%06d", random.nextInt(1_000_000));
      SealedSecret sealedToken =
          sealer.seal(task.channelId(), ChannelSecretPurpose.ACCESS_TOKEN, token);
      SealedSecret sealedPin =
          sealer.seal(task.channelId(), ChannelSecretPurpose.REGISTRATION_PIN, pin);
      tasks.storeSecrets(task.channelId(), sealedToken, sealedPin, clock.instant());
    }
    meta.register(token, requireNumber(task), pin);
    tasks.advance(task, "SUBSCRIBE_WEBHOOKS", clock.instant());
    LOG.info("WhatsApp number registered channel={}", task.channelId());
  }

  private void subscribe(LeasedTask task) {
    meta.subscribe(token(task), requireAccount(task));
    tasks.activate(task, clock.instant());
    LOG.info("WhatsApp channel active channel={}", task.channelId());
  }

  /**
   * Best effort on Meta's side: a revoked or refused token still disconnects here; an outage is
   * retried a few times, then the channel is disconnected anyway.
   */
  private void disconnect(LeasedTask task) {
    if ("DISCONNECTED".equals(task.channelStatus())) {
      tasks.drop(task, "ALREADY_DISCONNECTED", clock.instant());
      return;
    }
    // The subscription belongs to the whole account: keep it while another clinic's live channel
    // uses the same account (an agency's), or that clinic would stop receiving messages.
    if (task.token() != null
        && task.wabaId() != null
        && !tasks.otherLiveOnAccount(task.channelId(), task.wabaId())) {
      try {
        meta.unsubscribe(token(task), task.wabaId());
      } catch (MetaGraphException failure) {
        boolean retryable =
            failure.kind() == MetaGraphException.Kind.TRANSIENT
                || failure.kind() == MetaGraphException.Kind.NOT_CONFIGURED;
        if (retryable && task.attempts() + 1 < MAX_ATTEMPTS) {
          retry(task, failure.code());
          return;
        }
        LOG.warn(
            "WhatsApp unsubscribe skipped channel={} code={}", task.channelId(), failure.code());
      } catch (ChannelSecretException unreadable) {
        // Missing keys are a server problem: wait for them before wiping the token for good.
        if (unreadable.reason() == ChannelSecretException.Reason.NOT_CONFIGURED
            && task.attempts() + 1 < MAX_ATTEMPTS) {
          retry(task, "KEYS_NOT_CONFIGURED");
          return;
        }
        LOG.warn("WhatsApp unsubscribe skipped channel={} code=TOKEN_UNREADABLE", task.channelId());
      }
    }
    tasks.disconnect(task, clock.instant());
    LOG.info("WhatsApp channel disconnected channel={}", task.channelId());
  }

  private String token(LeasedTask task) {
    if (task.token() == null)
      throw new MetaGraphException(MetaGraphException.Kind.TOKEN_INVALID, "TOKEN_MISSING");
    return unsealer.open(task.channelId(), ChannelSecretPurpose.ACCESS_TOKEN, task.token());
  }

  private static String requireNumber(LeasedTask task) {
    if (task.phoneNumberId() == null) {
      throw new MetaGraphException(MetaGraphException.Kind.PERMANENT, "NUMBER_MISSING");
    }
    return task.phoneNumberId();
  }

  private static String requireAccount(LeasedTask task) {
    if (task.wabaId() == null) {
      throw new MetaGraphException(MetaGraphException.Kind.PERMANENT, "ACCOUNT_MISSING");
    }
    return task.wabaId();
  }

  private void failChannel(LeasedTask task, String code) {
    String safe = code.matches("[A-Z0-9_]{1,40}") ? code : "CONNECTION_FAILED";
    if ("DISCONNECT".equals(task.step())) {
      tasks.disconnect(task, clock.instant());
      return;
    }
    tasks.fail(task, safe, clock.instant());
    LOG.warn("WhatsApp channel failed channel={} code={}", task.channelId(), safe);
  }

  private void retry(LeasedTask task, String code) {
    int attempts = task.attempts() + 1;
    String safe = code.matches("[A-Z0-9_]{1,40}") ? code : "RETRY";
    if (attempts >= MAX_ATTEMPTS) {
      // The last cause, so the failure says what kept going wrong.
      failChannel(task, safe);
      return;
    }
    Instant at = clock.instant();
    tasks.retry(task, attempts, at.plus(InboundQueueRepository.delay(attempts)), safe, at);
    LOG.info(
        "WhatsApp channel task retry channel={} step={} code={}",
        task.channelId(),
        task.step(),
        code);
  }

  private record Candidate(String waba, MetaPhoneNumber number) {}

  @Override
  public String toString() {
    return "ChannelOnboardingProcessor";
  }
}
