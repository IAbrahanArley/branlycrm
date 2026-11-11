package br.com.branlycrm.report.repositories;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.report.domain.Dashboard;
import br.com.branlycrm.report.domain.MonthlyReport;
import br.com.branlycrm.report.domain.MonthlyReport.Count;
import br.com.branlycrm.report.domain.MonthlyReport.ProcedureRank;
import br.com.branlycrm.report.domain.MonthlyReport.ReasonCount;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.exceptions.ClinicDataException;
import br.com.branlycrm.tenant.persistence.ClinicPersistence;
import br.com.branlycrm.tenant.persistence.ClinicTenant;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.Session;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;

/**
 * Aggregated reads for the monthly report and the reception dashboard (CRM-051), exactly as
 * docs/metricas.md defines them. Each one runs in a single transaction of the authorized clinic, so
 * its numbers are consistent with each other. Read only.
 */
@Repository
public class ReportRepository {
  public static final YearMonth OLDEST = YearMonth.of(2000, 1);
  private static final List<String> ORIGINS =
      List.of("PAID_TRAFFIC", "INSTAGRAM_ORGANIC", "REFERRAL", "GOOGLE", "OTHER", "UNIDENTIFIED");
  private static final List<String> TEMPERATURES =
      List.of("COLD", "WARM", "HOT", "QUALIFIED", "NONE");

  /** A lead won in the period with an active sale (a cancelled sale leaves the lead won). */
  private static final String WON_WITH_SALE =
      "FROM leads l WHERE l.status = 'WON' AND l.closed_at >= :start AND l.closed_at < :end"
          + " AND EXISTS (SELECT 1 FROM sales s WHERE s.lead_id = l.id AND s.status = 'ACTIVE')";

  private final ClinicPersistence persistence;
  private final Clock clock;

  public ReportRepository(ClinicPersistence persistence, ObjectProvider<Clock> clock) {
    this.persistence = persistence;
    this.clock = clock.getIfAvailable(Clock::systemUTC);
  }

  /**
   * The report of {@code month}, or of the current month in the clinic's time zone when null.
   *
   * @throws InvalidInputException month, when before 2000 or after the current month
   */
  public MonthlyReport monthly(ClinicAccess access, YearMonth month) {
    Instant now = clock.instant();
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          ZoneId zone = zone(session);
          YearMonth current = YearMonth.from(LocalDate.ofInstant(now, zone));
          YearMonth target = month == null ? current : month;
          if (target.isBefore(OLDEST) || target.isAfter(current)) {
            throw new InvalidInputException("month");
          }
          Instant start = target.atDay(1).atStartOfDay(zone).toInstant();
          Instant end = target.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
          LocalDate first = target.atDay(1);
          LocalDate last = target.atEndOfMonth();
          long[] revenue = salesBetween(session, first, last);
          return new MonthlyReport(
              target,
              zone.getId(),
              count(
                  session,
                  "SELECT count(*) FROM contacts WHERE created_at >= :start AND created_at < :end",
                  start,
                  end),
              buckets(
                  session,
                  "SELECT origin_type, count(*) FROM leads"
                      + " WHERE opened_at >= :start AND opened_at < :end GROUP BY origin_type",
                  start,
                  end,
                  ORIGINS),
              buckets(
                  session,
                  "SELECT COALESCE(temperature, 'NONE'), count(*) FROM leads"
                      + " WHERE opened_at >= :start AND opened_at < :end GROUP BY 1",
                  start,
                  end,
                  TEMPERATURES),
              count(session, "SELECT count(*) " + WON_WITH_SALE, start, end),
              count(
                  session,
                  "SELECT count(*) FROM leads WHERE status = 'LOST'"
                      + " AND closed_at >= :start AND closed_at < :end",
                  start,
                  end),
              lostByReason(session, start, end),
              count(
                  session,
                  "SELECT count(*) "
                      + WON_WITH_SALE
                      + " AND EXISTS (SELECT 1 FROM lead_events e WHERE e.lead_id = l.id"
                      + " AND e.type = 'TEMPERATURE_CHANGED' AND e.to_value = 'COLD')",
                  start,
                  end),
              revenue[0],
              revenue[1],
              topProcedures(session, first, last));
        });
  }

  /** Today's reception numbers in the clinic's time zone. */
  public Dashboard dashboard(ClinicAccess access) {
    Instant now = clock.instant();
    return persistence.inTransaction(
        ClinicTenant.of(access),
        session -> {
          ZoneId zone = zone(session);
          LocalDate today = LocalDate.ofInstant(now, zone);
          Instant dayStart = today.atStartOfDay(zone).toInstant();
          Instant dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant();
          long[] salesToday = salesBetween(session, today, today);
          long[] salesMonth = salesBetween(session, today.withDayOfMonth(1), today);
          return new Dashboard(
              today,
              zone.getId(),
              count(
                  session,
                  "SELECT count(*) FROM leads WHERE opened_at >= :start AND opened_at < :end",
                  dayStart,
                  dayEnd),
              salesToday[0],
              salesToday[1],
              salesMonth[0],
              salesMonth[1],
              ((Number)
                      session
                          .createNativeQuery(
                              "SELECT count(*) FROM tasks WHERE status = 'OPEN' AND due_at < :now",
                              Long.class)
                          .setParameter("now", now)
                          .getSingleResult())
                  .longValue(),
              count(
                  session,
                  "SELECT count(*) FROM tasks WHERE status = 'OPEN'"
                      + " AND due_at >= :start AND due_at < :end",
                  now,
                  dayEnd));
        });
  }

  private static long count(Session session, String sql, Instant start, Instant end) {
    return ((Number)
            session
                .createNativeQuery(sql, Long.class)
                .setParameter("start", start)
                .setParameter("end", end)
                .getSingleResult())
        .longValue();
  }

  /** Every key appears, with zero when absent, in the given order. */
  private static List<Count> buckets(
      Session session, String sql, Instant start, Instant end, List<String> keys) {
    Map<String, Long> found = new HashMap<>();
    for (Object[] row :
        session
            .createNativeQuery(sql, Object[].class)
            .setParameter("start", start)
            .setParameter("end", end)
            .getResultList()) {
      found.put((String) row[0], ((Number) row[1]).longValue());
    }
    return keys.stream().map(key -> new Count(key, found.getOrDefault(key, 0L))).toList();
  }

  private static List<ReasonCount> lostByReason(Session session, Instant start, Instant end) {
    return session
        .createNativeQuery(
            "SELECT r.id, r.name, count(*) FROM leads l JOIN loss_reasons r"
                + " ON r.id = l.loss_reason_id WHERE l.status = 'LOST'"
                + " AND l.closed_at >= :start AND l.closed_at < :end"
                + " GROUP BY r.id, r.name ORDER BY count(*) DESC, r.name, r.id",
            Object[].class)
        .setParameter("start", start)
        .setParameter("end", end)
        .getResultList()
        .stream()
        .map(row -> new ReasonCount((UUID) row[0], (String) row[1], ((Number) row[2]).longValue()))
        .toList();
  }

  /** Count and sum of active sales with a sale day in [first, last]. */
  private static long[] salesBetween(Session session, LocalDate first, LocalDate last) {
    Object[] row =
        session
            .createNativeQuery(
                "SELECT count(*), COALESCE(sum(amount_cents), 0) FROM sales"
                    + " WHERE status = 'ACTIVE' AND sold_on BETWEEN :first AND :last",
                Object[].class)
            .setParameter("first", first)
            .setParameter("last", last)
            .getSingleResult();
    return new long[] {((Number) row[0]).longValue(), ((Number) row[1]).longValue()};
  }

  private static List<ProcedureRank> topProcedures(
      Session session, LocalDate first, LocalDate last) {
    return session
        .createNativeQuery(
            "SELECT p.id, p.name, count(*), sum(s.amount_cents) FROM sales s"
                + " JOIN procedures p ON p.id = s.procedure_id"
                + " WHERE s.status = 'ACTIVE' AND s.sold_on BETWEEN :first AND :last"
                + " GROUP BY p.id, p.name"
                + " ORDER BY count(*) DESC, sum(s.amount_cents) DESC, p.name, p.id LIMIT 10",
            Object[].class)
        .setParameter("first", first)
        .setParameter("last", last)
        .getResultList()
        .stream()
        .map(
            row ->
                new ProcedureRank(
                    (UUID) row[0],
                    (String) row[1],
                    ((Number) row[2]).longValue(),
                    ((Number) row[3]).longValue()))
        .toList();
  }

  private static ZoneId zone(Session session) {
    String zone =
        session
            .createNativeQuery("SELECT time_zone FROM clinic_settings", String.class)
            .getResultList()
            .stream()
            .findFirst()
            .orElseThrow(
                () -> new ClinicDataException(ClinicDataException.Reason.INVALID_DATA, null));
    try {
      return ZoneId.of(zone);
    } catch (DateTimeException invalid) {
      throw new ClinicDataException(ClinicDataException.Reason.INVALID_DATA, invalid);
    }
  }
}
