package br.com.branlycrm.catalog.domain;

import br.com.branlycrm.common.exceptions.InvalidInputException;

/** Editable data of a procedure (CRM-05, PAC-04). No clinical information. */
public record ProcedureFields(
    String name, long referencePriceCents, String category, Integer returnIntervalDays) {
  public static final int NAME_MAX = 120;
  public static final int CATEGORY_MAX = 60;

  /** R$ 10.000.000,00, the database check. */
  public static final long PRICE_MAX_CENTS = 1_000_000_000L;

  public static final int RETURN_MAX_DAYS = 3650;

  public ProcedureFields {
    name = CatalogText.required(name, NAME_MAX, "name");
    if (referencePriceCents < 0 || referencePriceCents > PRICE_MAX_CENTS) {
      throw new InvalidInputException("referencePriceCents");
    }
    category = CatalogText.optional(category, CATEGORY_MAX, "category");
    if (returnIntervalDays != null
        && (returnIntervalDays < 1 || returnIntervalDays > RETURN_MAX_DAYS)) {
      throw new InvalidInputException("returnIntervalDays");
    }
  }
}
