package br.com.branlycrm.pipeline.controllers;

import br.com.branlycrm.common.dto.response.PageView;
import br.com.branlycrm.identity.security.CrmPrincipal;
import br.com.branlycrm.identity.security.SelectedClinic;
import br.com.branlycrm.pipeline.dto.request.CancellationRequest;
import br.com.branlycrm.pipeline.dto.request.SaleRequest;
import br.com.branlycrm.pipeline.dto.response.SaleEventView;
import br.com.branlycrm.pipeline.dto.response.SaleView;
import br.com.branlycrm.pipeline.services.SaleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Sales of the clinic selected in the session (CRM-035). */
@Tag(name = "Sales", description = "Sales registration, correction, cancellation and history")
@SecurityRequirement(name = "session")
@RestController
@RequestMapping("/v1/clinic")
public final class SaleController {
  private final SaleService sales;

  public SaleController(SaleService sales) {
    this.sales = sales;
  }

  @Operation(
      operationId = "registerSale",
      summary = "Register the sale of an open lead and close it as won (version is the lead's)")
  @ApiResponse(responseCode = "201", description = "Sale registered")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT")
  @ApiResponse(responseCode = "404", description = "LEAD_NOT_FOUND")
  @ApiResponse(
      responseCode = "409",
      description =
          "LEAD_STALE, LEAD_CLOSED, SALE_PROCEDURE_UNAVAILABLE, SALE_PROFESSIONAL_UNAVAILABLE")
  @SecurityRequirement(name = "csrf")
  @PostMapping("/leads/{leadId}/sale")
  public ResponseEntity<SaleView> register(
      @PathVariable UUID leadId,
      @RequestBody SaleRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    SaleView sale =
        sales.register(SelectedClinic.require(session), principal.subjectId(), leadId, request);
    return ResponseEntity.created(
            ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/v1/clinic/sales/{saleId}")
                .buildAndExpand(sale.id())
                .toUri())
        .body(sale);
  }

  @Operation(operationId = "listSales", summary = "List sales, newest sale day first")
  @ApiResponse(responseCode = "200", description = "Page of sales")
  @GetMapping("/sales")
  public PageView<SaleView> list(
      @RequestParam(required = false) String contactId,
      @RequestParam(required = false) String leadId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String page,
      @RequestParam(required = false) String pageSize,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return sales.list(
        SelectedClinic.require(session),
        principal.subjectId(),
        contactId,
        leadId,
        status,
        from,
        to,
        page,
        pageSize);
  }

  @Operation(operationId = "getSale", summary = "Sale detail")
  @ApiResponse(responseCode = "200", description = "Sale")
  @ApiResponse(responseCode = "404", description = "SALE_NOT_FOUND")
  @GetMapping("/sales/{saleId}")
  public SaleView get(
      @PathVariable UUID saleId,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return sales.get(SelectedClinic.require(session), principal.subjectId(), saleId);
  }

  @Operation(operationId = "listSaleEvents", summary = "The sale's history, oldest first")
  @ApiResponse(responseCode = "200", description = "Events")
  @GetMapping("/sales/{saleId}/events")
  public List<SaleEventView> events(
      @PathVariable UUID saleId,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return sales.history(SelectedClinic.require(session), principal.subjectId(), saleId);
  }

  @Operation(
      operationId = "correctSale",
      summary = "Correct an active sale with a reason (OWNER, MANAGER)")
  @ApiResponse(responseCode = "200", description = "Sale corrected")
  @ApiResponse(
      responseCode = "409",
      description =
          "SALE_STALE, SALE_CANCELLED, SALE_PROCEDURE_UNAVAILABLE, SALE_PROFESSIONAL_UNAVAILABLE")
  @SecurityRequirement(name = "csrf")
  @PutMapping("/sales/{saleId}")
  public SaleView correct(
      @PathVariable UUID saleId,
      @RequestBody SaleRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return sales.correct(SelectedClinic.require(session), principal.subjectId(), saleId, request);
  }

  @Operation(
      operationId = "cancelSale",
      summary = "Cancel an active sale with a reason (OWNER, MANAGER); the lead stays won")
  @ApiResponse(responseCode = "200", description = "Sale cancelled")
  @ApiResponse(responseCode = "409", description = "SALE_STALE, SALE_CANCELLED")
  @SecurityRequirement(name = "csrf")
  @PostMapping("/sales/{saleId}/cancellation")
  public SaleView cancel(
      @PathVariable UUID saleId,
      @RequestBody CancellationRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return sales.cancel(SelectedClinic.require(session), principal.subjectId(), saleId, request);
  }
}
