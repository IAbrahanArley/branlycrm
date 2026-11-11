package br.com.branlycrm.report.controllers;

import br.com.branlycrm.identity.security.CrmPrincipal;
import br.com.branlycrm.identity.security.SelectedClinic;
import br.com.branlycrm.report.dto.response.DashboardView;
import br.com.branlycrm.report.dto.response.MonthlyReportView;
import br.com.branlycrm.report.services.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Monthly report and reception dashboard of the selected clinic (CRM-051, docs/metricas.md). */
@Tag(name = "Reports", description = "Monthly report and reception dashboard")
@SecurityRequirement(name = "session")
@RestController
@RequestMapping("/v1/clinic")
public final class ReportController {
  private final ReportService reports;

  public ReportController(ReportService reports) {
    this.reports = reports;
  }

  @Operation(
      operationId = "getMonthlyReport",
      summary = "Monthly report (OWNER, MANAGER, VIEWER); month=yyyy-MM, default current month")
  @ApiResponse(responseCode = "200", description = "Report")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: month (format, future, < 2000)")
  @GetMapping("/reports/monthly")
  public MonthlyReportView monthly(
      @RequestParam(required = false) String month,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return reports.monthly(SelectedClinic.require(session), principal.subjectId(), month);
  }

  @Operation(operationId = "getDashboard", summary = "Reception dashboard for today (all roles)")
  @ApiResponse(responseCode = "200", description = "Dashboard")
  @GetMapping("/dashboard")
  public DashboardView dashboard(
      @AuthenticationPrincipal CrmPrincipal principal, HttpSession session) {
    return reports.dashboard(SelectedClinic.require(session), principal.subjectId());
  }
}
