package br.com.branlycrm.whatsapp.services;

import br.com.branlycrm.agency.services.AgencyService;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.tenant.domain.ClinicPermission;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.services.TenantAccessService;
import br.com.branlycrm.whatsapp.domain.ChannelState;
import br.com.branlycrm.whatsapp.dto.request.ConnectChannelRequest;
import br.com.branlycrm.whatsapp.dto.response.ChannelView;
import br.com.branlycrm.whatsapp.exceptions.ChannelException;
import br.com.branlycrm.whatsapp.exceptions.ChannelException.Reason;
import br.com.branlycrm.whatsapp.meta.MetaGraphClient;
import br.com.branlycrm.whatsapp.meta.MetaGraphException;
import br.com.branlycrm.whatsapp.repositories.ChannelConnectionRepository;
import br.com.branlycrm.whatsapp.secrets.ChannelSealer;
import br.com.branlycrm.whatsapp.secrets.ChannelSecretException;
import br.com.branlycrm.whatsapp.secrets.ChannelSecretPurpose;
import br.com.branlycrm.whatsapp.secrets.SealedSecret;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Connection of the clinic's WhatsApp number (CRM-081b, ADR-036), for the clinic's owner or manager
 * (CHANNEL_CONNECT) and for the admin of the agency whose portfolio holds the clinic.
 *
 * <ul>
 *   <li>The clinic comes from the authorized membership or the portfolio, never from the request.
 *   <li>The signup code is exchanged at once and the token sealed with the public key; neither is
 *       stored nor logged in clear. Account and number are confirmed by the worker from the token.
 *   <li>Only the worker changes a channel's status: the API creates a PENDING channel and tasks.
 * </ul>
 */
@Service
public class WhatsappChannelService {
  private static final Logger LOG = LoggerFactory.getLogger(WhatsappChannelService.class);

  private final TenantAccessService access;
  private final AgencyService agencies;
  private final ChannelConnectionRepository channels;
  private final MetaGraphClient meta;
  private final ChannelSealer sealer;
  private final Clock clock;

  public WhatsappChannelService(
      TenantAccessService access,
      AgencyService agencies,
      ChannelConnectionRepository channels,
      MetaGraphClient meta,
      ChannelSealer sealer,
      ObjectProvider<Clock> clock) {
    this.access = access;
    this.agencies = agencies;
    this.channels = channels;
    this.meta = meta;
    this.sealer = sealer;
    this.clock = clock.getIfAvailable(Clock::systemUTC);
  }

  /** The selected clinic's channel (CHANNEL_VIEW: owner and manager). */
  public ChannelView status(UUID tenantId, UUID actor) {
    access.require(tenantId, actor, ClinicPermission.CHANNEL_VIEW);
    return view(channels.liveChannel(tenantId));
  }

  public ChannelView connect(UUID tenantId, UUID actor, ConnectChannelRequest request) {
    access.require(tenantId, actor, ClinicPermission.CHANNEL_CONNECT);
    return doConnect(tenantId, actor, request);
  }

  public ChannelView disconnect(UUID tenantId, UUID actor) {
    access.require(tenantId, actor, ClinicPermission.CHANNEL_CONNECT);
    return doDisconnect(tenantId, actor);
  }

  /** A clinic of the agency's portfolio; another agency's clinic answers like a missing one. */
  public ChannelView agencyStatus(UUID agencyId, UUID tenantId, UUID actor) {
    requirePortfolio(agencyId, tenantId, actor);
    return view(channels.liveChannel(tenantId));
  }

  public ChannelView agencyConnect(
      UUID agencyId, UUID tenantId, UUID actor, ConnectChannelRequest request) {
    requirePortfolio(agencyId, tenantId, actor);
    return doConnect(tenantId, actor, request);
  }

  public ChannelView agencyDisconnect(UUID agencyId, UUID tenantId, UUID actor) {
    requirePortfolio(agencyId, tenantId, actor);
    return doDisconnect(tenantId, actor);
  }

  private ChannelView doConnect(UUID tenantId, UUID actor, ConnectChannelRequest request) {
    if (request == null || request.code() == null || request.code().isBlank()) {
      throw new InvalidInputException("code");
    }
    String phone = request.phoneNumberId();
    if (phone != null && !phone.matches("[0-9]{1,32}")) {
      throw new InvalidInputException("phoneNumberId");
    }
    if (!sealer.configured() || !meta.configured()) {
      throw new ChannelException(Reason.NOT_CONFIGURED);
    }
    // Before spending the single-use code: a clinic has one live channel (ADR-036).
    if (channels.liveChannel(tenantId).isPresent()) {
      throw new ChannelException(Reason.ALREADY_CONNECTED);
    }
    UUID channelId = UUID.randomUUID();
    SealedSecret sealed;
    try {
      String token = meta.exchangeCode(request.code().strip());
      sealed = sealer.seal(channelId, ChannelSecretPurpose.ACCESS_TOKEN, token);
    } catch (MetaGraphException refused) {
      LOG.warn(
          "WHATSAPP_CONNECT_REFUSED tenant={} subject={} code={}", tenantId, actor, refused.code());
      throw new ChannelException(
          switch (refused.kind()) {
            case PERMANENT, TOKEN_INVALID -> Reason.CODE_INVALID;
            case NOT_CONFIGURED -> Reason.NOT_CONFIGURED;
            case TRANSIENT -> Reason.META_UNAVAILABLE;
          });
    } catch (ChannelSecretException unsealable) {
      throw new ChannelException(Reason.NOT_CONFIGURED);
    }
    boolean created =
        channels.createPending(
            channelId,
            tenantId,
            sealed,
            actor,
            phone,
            Boolean.TRUE.equals(request.coexistence()),
            clock.instant());
    if (!created) throw new ChannelException(Reason.ALREADY_CONNECTED);
    LOG.info(
        "WHATSAPP_CONNECT_REQUESTED tenant={} subject={} channel={}", tenantId, actor, channelId);
    return view(channels.liveChannel(tenantId));
  }

  private ChannelView doDisconnect(UUID tenantId, UUID actor) {
    ChannelState live =
        channels
            .liveChannel(tenantId)
            .orElseThrow(() -> new ChannelException(Reason.NOT_CONNECTED));
    if (!channels.requestDisconnect(live.channelId(), actor, clock.instant())) {
      throw new ChannelException(Reason.BUSY);
    }
    LOG.info(
        "WHATSAPP_DISCONNECT_REQUESTED tenant={} subject={} channel={}",
        tenantId,
        actor,
        live.channelId());
    return view(channels.liveChannel(tenantId));
  }

  private void requirePortfolio(UUID agencyId, UUID tenantId, UUID actor) {
    agencies.requireAdmin(agencyId, actor);
    if (tenantId == null || !channels.inPortfolio(agencyId, tenantId)) {
      throw new TenantAccessException();
    }
  }

  private static ChannelView view(Optional<ChannelState> live) {
    if (live.isEmpty()) return ChannelView.none();
    ChannelState state = live.get();
    String status;
    if ("DISCONNECT".equals(state.openStep())) {
      status = "DISCONNECTING";
    } else if ("PENDING".equals(state.status())) {
      status = "CONNECTING";
    } else {
      status = state.status();
    }
    return new ChannelView(
        status,
        state.displayPhoneNumber(),
        state.failureCode(),
        state.connectedAt(),
        state.updatedAt());
  }
}
