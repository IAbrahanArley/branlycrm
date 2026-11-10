package br.com.branlycrm.pipeline.domain;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import java.time.LocalDate;
import java.util.UUID;

/** Filters of the sale list; every one optional. Days are inclusive. */
public record SaleSearch(
    UUID contactId, UUID leadId, SaleStatus status, LocalDate from, LocalDate to) {
  public SaleSearch {
    if (from != null && to != null && from.isAfter(to)) throw new InvalidInputException("from");
  }
}
