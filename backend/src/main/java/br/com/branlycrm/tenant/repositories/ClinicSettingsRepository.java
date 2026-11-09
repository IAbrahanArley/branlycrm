package br.com.branlycrm.tenant.repositories;

import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.domain.ClinicSettings;
import br.com.branlycrm.tenant.exceptions.ClinicDataException;
import br.com.branlycrm.tenant.persistence.ClinicPersistence;
import br.com.branlycrm.tenant.persistence.ClinicSettingsEntity;
import br.com.branlycrm.tenant.persistence.ClinicTenant;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;

/**
 * Settings of the clinic authorized by a {@link ClinicAccess}, read and written in that clinic's
 * own database only. Permission checks belong to the calling service.
 */
@Repository
public class ClinicSettingsRepository {
  private final ClinicPersistence persistence;

  public ClinicSettingsRepository(ClinicPersistence persistence) {
    this.persistence = persistence;
  }

  public ClinicSettings find(ClinicAccess access) {
    return persistence.inTransaction(ClinicTenant.of(access), session -> settings(row(session)));
  }

  public ClinicSettings changeTimeZone(ClinicAccess access, ClinicSettings settings) {
    String zone = settings.timeZone().getId();
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          ClinicSettingsEntity row = row(session);
          row.changeTimeZone(zone);
          session.flush();
          return settings(row);
        });
  }

  /** The row is created by the migration; its absence means the clinic database is outdated. */
  private static ClinicSettingsEntity row(Session session) {
    ClinicSettingsEntity row = session.find(ClinicSettingsEntity.class, ClinicSettingsEntity.KEY);
    if (row == null) {
      throw new ClinicDataException(ClinicDataException.Reason.SCHEMA_OUTDATED, null);
    }
    return row;
  }

  /** A stored zone the domain refuses was written outside the application. */
  private static ClinicSettings settings(ClinicSettingsEntity row) {
    try {
      return ClinicSettings.of(row.timeZone());
    } catch (IllegalArgumentException invalid) {
      throw new ClinicDataException(ClinicDataException.Reason.INVALID_DATA, invalid);
    }
  }
}
