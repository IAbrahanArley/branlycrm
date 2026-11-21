package br.com.branlycrm.tenant.domain;

import br.com.branlycrm.common.exceptions.InvalidInputException;

/**
 * Name of a clinic: trimmed, 1 to 160 characters, like tenants.clinic_name (V2), and without
 * control or invisible format characters such as bidi overrides (it goes into e-mail subjects).
 */
public record ClinicName(String value) {
  public ClinicName {
    if (value == null) throw new InvalidInputException("clinicName");
    value = value.strip();
    if (value.isEmpty()
        || value.length() > 160
        || value
            .codePoints()
            .anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)) {
      throw new InvalidInputException("clinicName");
    }
  }
}
