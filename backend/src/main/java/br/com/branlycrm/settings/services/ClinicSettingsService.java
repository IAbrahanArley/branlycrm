package br.com.branlycrm.settings.services;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.settings.dto.response.ClinicSettingsView;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.domain.ClinicName;
import br.com.branlycrm.tenant.domain.ClinicPermission;
import br.com.branlycrm.tenant.domain.ClinicSettings;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.repositories.ClinicSettingsRepository;
import br.com.branlycrm.tenant.repositories.TenantRegistryRepository;
import br.com.branlycrm.tenant.services.TenantAccessService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Name and time zone of the selected clinic (CRM-013). Every role reads them
 * (CLINIC_SETTINGS_VIEW); only the owner changes them (CLINIC_SETTINGS_EDIT).
 *
 * <p>The name lives in the control database and the zone in the clinic database, so each one is
 * changed by its own operation and a failure never leaves half of a change applied.
 */
@Service
public class ClinicSettingsService {
  private static final Logger log = LoggerFactory.getLogger(ClinicSettingsService.class);

  private final TenantAccessService access;
  private final TenantRegistryRepository registry;
  private final ClinicSettingsRepository settings;

  public ClinicSettingsService(
      TenantAccessService access,
      TenantRegistryRepository registry,
      ClinicSettingsRepository settings) {
    this.access = access;
    this.registry = registry;
    this.settings = settings;
  }

  public ClinicSettingsView get(UUID tenantId, UUID actor) {
    return view(access.require(tenantId, actor, ClinicPermission.CLINIC_SETTINGS_VIEW));
  }

  public ClinicSettingsView rename(UUID tenantId, UUID actor, String clinicName) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.CLINIC_SETTINGS_EDIT);
    ClinicName name = new ClinicName(clinicName);
    // Rechecked in the UPDATE itself: a clinic suspended or an owner removed since the access check
    // is refused like any other denial.
    if (!registry.renameClinic(clinic.tenantId(), actor, name.value())) {
      throw new TenantAccessException();
    }
    // Stable code and ids only; the name stays out of logs. Full audit trail is CRM-070.
    log.info("CLINIC_RENAMED tenant={} subject={}", clinic.tenantId(), actor);
    return view(clinic);
  }

  /**
   * Changes the zone used from now on for "today", tasks due and report months. Dates already
   * stored, such as the sale day, keep the value they were recorded with.
   */
  public ClinicSettingsView changeTimeZone(UUID tenantId, UUID actor, String timeZone) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.CLINIC_SETTINGS_EDIT);
    ClinicSettings changed;
    try {
      changed = ClinicSettings.of(timeZone);
    } catch (IllegalArgumentException invalid) {
      throw new InvalidInputException("timeZone");
    }
    settings.changeTimeZone(clinic, changed);
    log.info(
        "CLINIC_TIME_ZONE_CHANGED tenant={} subject={} zone={}",
        clinic.tenantId(),
        actor,
        changed.timeZone().getId());
    return view(clinic);
  }

  private ClinicSettingsView view(ClinicAccess clinic) {
    String name =
        registry.findClinicName(clinic.tenantId()).orElseThrow(TenantAccessException::new);
    return new ClinicSettingsView(name, settings.find(clinic).timeZone().getId());
  }
}
