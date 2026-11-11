package br.com.branlycrm.report.domain;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * The monthly report of a clinic as defined in docs/metricas.md (CRM-050). Only aggregated numbers
 * and catalog names: no contact data.
 */
public record MonthlyReport(
    YearMonth month,
    String timeZone,
    long contactsCreated,
    List<Count> leadsByOrigin,
    List<Count> leadsByTemperature,
    long salesClosed,
    long lost,
    List<ReasonCount> lostByReason,
    long coldReverted,
    long salesCount,
    long revenueCents,
    List<ProcedureRank> topProcedures) {
  /** A count per key (origin type or temperature, NONE when not set). */
  public record Count(String key, long count) {}

  public record ReasonCount(UUID id, String name, long count) {}

  public record ProcedureRank(UUID id, String name, long count, long amountCents) {}

  public MonthlyReport {
    leadsByOrigin = List.copyOf(leadsByOrigin);
    leadsByTemperature = List.copyOf(leadsByTemperature);
    lostByReason = List.copyOf(lostByReason);
    topProcedures = List.copyOf(topProcedures);
  }

  /**
   * Closed sales ÷ (closed sales + lost), in percent with one decimal; null when no lead was closed
   * in the month (shown as "—", never 0%).
   */
  public Double conversionPercent() {
    long closed = salesClosed + lost;
    if (closed == 0) return null;
    return Math.round(salesClosed * 1000.0 / closed) / 10.0;
  }
}
