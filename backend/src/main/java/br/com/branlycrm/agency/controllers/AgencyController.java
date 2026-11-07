package br.com.branlycrm.agency.controllers;

import br.com.branlycrm.agency.dto.response.AgencyView;
import br.com.branlycrm.agency.dto.response.PortfolioClinicView;
import br.com.branlycrm.agency.services.AgencyService;
import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.common.dto.response.PageView;
import br.com.branlycrm.identity.security.CrmPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Agencies of the authenticated admin and their portfolio (ADR-026). */
@Tag(name = "Agencies", description = "Partner agencies and their clinic portfolio")
@SecurityRequirement(name = "session")
@RestController
@RequestMapping("/v1/agencies")
public final class AgencyController {
  private final AgencyService agencies;

  public AgencyController(AgencyService agencies) {
    this.agencies = agencies;
  }

  @Operation(summary = "Active agencies the authenticated user administers")
  @GetMapping
  public List<AgencyView> list(@AuthenticationPrincipal CrmPrincipal principal) {
    return agencies.list(principal.subjectId());
  }

  @Operation(summary = "Clinics of an agency portfolio, registry data only, paginated")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: agencyId, page or pageSize")
  @ApiResponse(
      responseCode = "404",
      description = "AGENCY_NOT_FOUND: missing, suspended or not administered by the user")
  @GetMapping("/{agencyId}/clinics")
  public PageView<PortfolioClinicView> portfolio(
      @PathVariable UUID agencyId,
      @Parameter(description = "1-based page, default 1") @RequestParam(required = false)
          String page,
      @Parameter(description = "Items per page, 1 to 100, default 25")
          @RequestParam(required = false)
          String pageSize,
      @AuthenticationPrincipal CrmPrincipal principal) {
    return agencies.portfolio(agencyId, principal.subjectId(), PageQuery.of(page, pageSize));
  }
}
