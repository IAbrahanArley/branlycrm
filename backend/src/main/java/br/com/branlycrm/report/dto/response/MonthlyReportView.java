package br.com.branlycrm.report.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * The monthly report (docs/metricas.md). {@code month} is yyyy-MM in the clinic's {@code timeZone};
 * {@code conversionPercent} is null when no lead was closed in the month. Amounts are registered
 * revenue in cents, never received revenue.
 */
public record MonthlyReportView(
    String month,
    String timeZone,
    long contactsCreated,
    List<CountView> leadsByOrigin,
    List<CountView> leadsByTemperature,
    long salesClosed,
    long lost,
    Double conversionPercent,
    List<ReasonCountView> lostByReason,
    long coldReverted,
    long salesCount,
    long revenueCents,
    List<ProcedureRankView> topProcedures) {
  public record CountView(String key, long count) {}

  public record ReasonCountView(UUID id, String name, long count) {}

  public record ProcedureRankView(UUID id, String name, long count, long amountCents) {}
}
