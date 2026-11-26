package br.com.branlycrm.whatsapp.worker;

import br.com.branlycrm.tenant.domain.ChannelAccess;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.repositories.TenantRegistryRepository;
import br.com.branlycrm.whatsapp.domain.MessageTemplate;
import br.com.branlycrm.whatsapp.meta.MetaGraphClient;
import br.com.branlycrm.whatsapp.meta.MetaGraphException;
import br.com.branlycrm.whatsapp.repositories.ChannelRegistryRepository;
import br.com.branlycrm.whatsapp.repositories.OutboundChannelRepository;
import br.com.branlycrm.whatsapp.repositories.TemplateRepository;
import br.com.branlycrm.whatsapp.secrets.ChannelSecretException;
import br.com.branlycrm.whatsapp.secrets.ChannelSecretPurpose;
import br.com.branlycrm.whatsapp.secrets.ChannelUnsealer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Copies the message templates of each active channel's account into its clinic (CRM-085): a
 * channel never copied, one whose copy was requested (webhook or "sync now"), and every {@link
 * #EVERY}. A failed copy is tried again after {@link #RETRY}. Logs carry ids and counts only.
 */
@Component
@ConditionalOnProperty(name = "app.whatsapp.worker.enabled", havingValue = "true")
public class TemplateSynchronizer {
  static final Duration EVERY = Duration.ofHours(6);
  static final Duration RETRY = Duration.ofMinutes(30);
  static final int BATCH = 5;
  private static final Logger LOG = LoggerFactory.getLogger(TemplateSynchronizer.class);

  private final TenantRegistryRepository tenants;
  private final ChannelRegistryRepository channels;
  private final OutboundChannelRepository senders;
  private final TemplateRepository templates;
  private final ChannelUnsealer unsealer;
  private final MetaGraphClient meta;
  private final Clock clock;

  public TemplateSynchronizer(
      TenantRegistryRepository tenants,
      ChannelRegistryRepository channels,
      OutboundChannelRepository senders,
      TemplateRepository templates,
      ChannelUnsealer unsealer,
      MetaGraphClient meta,
      ObjectProvider<Clock> clock) {
    this.tenants = tenants;
    this.channels = channels;
    this.senders = senders;
    this.templates = templates;
    this.unsealer = unsealer;
    this.meta = meta;
    this.clock = clock.getIfAvailable(Clock::systemUTC);
  }

  /** Copies the due channels, up to {@link #BATCH}; returns how many were copied. */
  public int syncDue() {
    int copied = 0;
    for (UUID channel : channels.channelsDueForTemplates(EVERY, BATCH)) {
      if (sync(channel)) copied++;
    }
    return copied;
  }

  /** Copies one channel's templates; false when it could not (tried again later). */
  public boolean sync(UUID channelId) {
    // The database's clock: the request it is compared with was stamped there too.
    Instant started = channels.databaseNow();
    Optional<ChannelAccess> access = tenants.findChannelAccess(channelId);
    Optional<OutboundChannelRepository.Sender> sender = senders.sender(channelId);
    if (access.isEmpty() || sender.isEmpty() || sender.get().wabaId() == null) {
      channels.templatesFailed(channelId, EVERY, RETRY);
      return false;
    }
    try {
      String token =
          unsealer.open(channelId, ChannelSecretPurpose.ACCESS_TOKEN, sender.get().token());
      MetaGraphClient.TemplateList list = meta.templates(token, sender.get().wabaId());
      List<MessageTemplate> found = list.templates();
      templates.replace(access.get(), found, list.unreadable(), clock.instant());
      channels.templatesSynced(channelId, started);
      LOG.info(
          "WhatsApp templates synced channel={} templates={} approved={}",
          channelId,
          found.size(),
          found.stream().filter(MessageTemplate::approved).count());
      return true;
    } catch (MetaGraphException failure) {
      LOG.warn("WhatsApp templates sync failed channel={} code={}", channelId, failure.code());
    } catch (ChannelSecretException unreadable) {
      LOG.error("WhatsApp templates sync failed channel={} code=TOKEN_UNREADABLE", channelId);
    } catch (br.com.branlycrm.tenant.exceptions.ClinicDataException failure) {
      LOG.warn(
          "WhatsApp templates sync failed channel={} code={}", channelId, failure.getMessage());
    } catch (TenantAccessException deactivated) {
      LOG.info("WhatsApp templates sync skipped reason=CHANNEL_INACTIVE channel={}", channelId);
    } catch (RuntimeException failure) {
      LOG.warn(
          "WhatsApp templates sync failed channel={} code={}",
          channelId,
          failure.getClass().getSimpleName());
    }
    channels.templatesFailed(channelId, EVERY, RETRY);
    return false;
  }
}
