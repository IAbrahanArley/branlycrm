package br.com.branlycrm.tenant.domain;

import java.util.UUID;

/**
 * Service access to a clinic database authorized by an active WhatsApp channel, not by a user
 * (ADR-036). Built only by the registry query that checks the channel and the clinic are ACTIVE;
 * the tenant always comes from the channel registry, never from a request payload.
 */
public record ChannelAccess(TenantDatabase database, UUID channelId) {
  public ChannelAccess {
    if (database == null || channelId == null) {
      throw new IllegalArgumentException("Incomplete channel access");
    }
  }

  public UUID tenantId() {
    return database.tenantId();
  }
}
