package br.com.branlycrm.onboarding.domain;

import br.com.branlycrm.common.exceptions.InvalidInputException;

/**
 * Name of a new clinic: trimmed, 1 to 160 characters, like tenants.clinic_name (V2), and without
 * control characters (it goes into e-mail subjects).
 */
public record ClinicName(String value) {
  public ClinicName {
    if (value == null) throw new InvalidInputException("clinicName");
    value = value.strip();
    if (value.isEmpty()
        || value.length() > 160
        || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new InvalidInputException("clinicName");
    }
  }
}
