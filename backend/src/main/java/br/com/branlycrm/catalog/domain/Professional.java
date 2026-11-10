package br.com.branlycrm.catalog.domain;

import java.util.UUID;

/**
 * A professional of the clinic who performs procedures, without a login (CRM-05). Only the name is
 * kept: no registration number, specialty or clinical information.
 */
public record Professional(UUID id, String name, boolean active, long version) {
  public static final int NAME_MAX = 120;

  public Professional {
    if (id == null || version < 0) throw new IllegalArgumentException("Incomplete professional");
    name = name(name);
  }

  /** Validates a professional's name from the client. */
  public static String name(String value) {
    return CatalogText.required(value, NAME_MAX, "name");
  }
}
