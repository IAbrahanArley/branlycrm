package br.com.branlycrm.report.services;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.report.domain.Dashboard;
import br.com.branlycrm.report.domain.MonthlyReport;
import br.com.branlycrm.report.dto.response.DashboardView;
import br.com.branlycrm.report.dto.response.MonthlyReportView;
import br.com.branlycrm.report.dto.response.MonthlyReportView.CountView;
import br.com.branlycrm.report.dto.response.MonthlyReportView.ProcedureRankView;
import br.com.branlycrm.report.dto.response.MonthlyReportView.ReasonCountView;
import br.com.branlycrm.report.repositories.ReportRepository;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.domain.ClinicPermission;
import br.com.branlycrm.tenant.services.TenantAccessService;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Monthly report (MONTHLY_REPORT_VIEW: owner, manager and viewer) and reception dashboard
 * (RECEPTION_DASHBOARD_VIEW: every role) of the selected clinic (CRM-051).
 */
@Service
public class ReportService {
  private final TenantAccessService access;
  private final ReportRepository reports;

  public ReportService(TenantAccessService access, ReportRepository reports) {
    this.access = access;
    this.reports = reports;
  }

  /** {@code month} is yyyy-MM; empty means the current month in the clinic's time zone. */
  public MonthlyReportView monthly(UUID tenantId, UUID actor, String month) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.MONTHLY_REPORT_VIEW);
    YearMonth target = month(month);
    MonthlyReport report = reports.monthly(clinic, target);
    return new MonthlyReportView(
        report.month().toString(),
        report.timeZone(),
        report.contactsCreated(),
        report.leadsByOrigin().stream().map(c -> new CountView(c.key(), c.count())).toList(),
        report.leadsByTemperature().stream().map(c -> new CountView(c.key(), c.count())).toList(),
        report.salesClosed(),
        report.lost(),
        report.conversionPercent(),
        report.lostByReason().stream()
            .map(r -> new ReasonCountView(r.id(), r.name(), r.count()))
            .toList(),
        report.coldReverted(),
        report.salesCount(),
        report.revenueCents(),
        report.topProcedures().stream()
            .map(p -> new ProcedureRankView(p.id(), p.name(), p.count(), p.amountCents()))
            .toList());
  }

  public DashboardView dashboard(UUID tenantId, UUID actor) {
    ClinicAccess clinic =
        access.require(tenantId, actor, ClinicPermission.RECEPTION_DASHBOARD_VIEW);
    Dashboard dashboard = reports.dashboard(clinic);
    return new DashboardView(
        dashboard.today(),
        dashboard.timeZone(),
        dashboard.leadsToday(),
        dashboard.salesTodayCount(),
        dashboard.salesTodayCents(),
        dashboard.salesMonthCount(),
        dashboard.salesMonthCents(),
        dashboard.tasksOverdue(),
        dashboard.tasksToday());
  }

  private static YearMonth month(String value) {
    if (value == null || value.isEmpty()) return null;
    try {
      return YearMonth.parse(value);
    } catch (DateTimeParseException invalid) {
      throw new InvalidInputException("month");
    }
  }
}
