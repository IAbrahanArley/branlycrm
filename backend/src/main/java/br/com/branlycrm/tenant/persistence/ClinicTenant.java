package br.com.branlycrm.tenant.persistence;

import br.com.branlycrm.tenant.domain.ChannelAccess;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import java.util.UUID;

/**
 * Hibernate tenant identifier of a clinic session: the clinic and either the authenticated subject
 * whose membership authorizes it, or the active WhatsApp channel of a service access (ADR-036).
 * Built only by {@link #of(ClinicAccess)} or {@link #ofChannel(ChannelAccess)} (ArchUnit forbids
 * other callers of the constructor and limits ofChannel to the WhatsApp webhook and worker). Even a
 * forged value grants nothing: every connection re-checks the membership or the channel.
 */
public record ClinicTenant(UUID tenantId, UUID subjectId, UUID channelId) {
  public ClinicTenant {
    if (tenantId == null || (subjectId == null) == (channelId == null)) {
      throw new IllegalArgumentException("Incomplete clinic tenant");
    }
  }

  public static ClinicTenant of(ClinicAccess access) {
    return new ClinicTenant(access.tenantId(), access.subjectId(), null);
  }

  public static ClinicTenant ofChannel(ChannelAccess access) {
    return new ClinicTenant(access.tenantId(), null, access.channelId());
  }

  public boolean service() {
    return channelId != null;
  }

  /** Keeps the subject out of logs and exception messages. */
  @Override
  public String toString() {
    return "ClinicTenant[tenantId=" + tenantId + "]";
  }
}
