package br.com.branlycrm.catalog.domain;

import java.util.UUID;

/** A procedure of the clinic. Inactive ones are kept on old sales and refused on new ones. */
public record Procedure(UUID id, ProcedureFields fields, boolean active, long version) {
  public Procedure {
    if (id == null || fields == null || version < 0) {
      throw new IllegalArgumentException("Incomplete procedure");
    }
  }
}
