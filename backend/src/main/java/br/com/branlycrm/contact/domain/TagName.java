package br.com.branlycrm.contact.domain;

import br.com.branlycrm.common.exceptions.InvalidInputException;

/** Manual tag label (PAC-06), unique per clinic regardless of case. */
public record TagName(String value) {
  public static final int MAX = 50;

  public TagName {
    value = value == null ? null : value.strip();
    if (value == null
        || value.isEmpty()
        || value.length() > MAX
        || ContactFields.INVISIBLE.matcher(value).find()) {
      throw new InvalidInputException("name");
    }
  }
}
