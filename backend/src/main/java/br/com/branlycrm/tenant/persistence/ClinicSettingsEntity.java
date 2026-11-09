package br.com.branlycrm.tenant.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

/**
 * The single row of clinic_settings (tenant migration V3). The runtime may update only the time
 * zone column, so the key is neither inserted nor updated and only changed columns are written.
 */
@Entity
@Table(name = "clinic_settings")
@DynamicUpdate
public class ClinicSettingsEntity {
  /** The primary key of the only row. */
  public static final boolean KEY = true;

  @Id
  @Column(name = "singleton", insertable = false, updatable = false)
  private boolean singleton;

  @Column(name = "time_zone", nullable = false, length = 64)
  private String timeZone;

  protected ClinicSettingsEntity() {}

  public String timeZone() {
    return timeZone;
  }

  public void changeTimeZone(String timeZone) {
    this.timeZone = timeZone;
  }
}
