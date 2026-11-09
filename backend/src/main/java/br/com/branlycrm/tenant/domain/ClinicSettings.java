package br.com.branlycrm.tenant.domain;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.regex.Pattern;

/**
 * Settings kept in the clinic's own database. Timestamps are stored in UTC and shown in this zone.
 *
 * <p>Only IANA Area/Location names known to the JDK are accepted: no fixed offsets ("+03:00",
 * "UTC+3"), no bare "UTC"/"GMT" and no "Etc/" aliases, so a clinic always follows its region's
 * daylight-saving rules. Legacy links such as "Brazil/East" are still accepted by the JDK and kept.
 */
public record ClinicSettings(ZoneId timeZone) {
  public static final ZoneId DEFAULT_TIME_ZONE = ZoneId.of("America/Sao_Paulo");

  private static final Pattern REGION = Pattern.compile("[A-Za-z]+(/[A-Za-z0-9_+-]+)+");

  public ClinicSettings {
    if (timeZone == null || !isRegion(timeZone.getId())) {
      throw new IllegalArgumentException("Invalid clinic time zone");
    }
  }

  /** Parses a zone name sent by a client or read from the clinic database. */
  public static ClinicSettings of(String timeZone) {
    if (timeZone == null || timeZone.length() > 64) {
      throw new IllegalArgumentException("Invalid clinic time zone");
    }
    try {
      return new ClinicSettings(ZoneId.of(timeZone));
    } catch (DateTimeException invalid) {
      throw new IllegalArgumentException("Invalid clinic time zone");
    }
  }

  private static boolean isRegion(String id) {
    return id.length() <= 64
        && REGION.matcher(id).matches()
        && !id.startsWith("Etc/")
        && !id.startsWith("SystemV/")
        && ZoneId.getAvailableZoneIds().contains(id);
  }
}
