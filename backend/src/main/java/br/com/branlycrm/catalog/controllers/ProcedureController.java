package br.com.branlycrm.catalog.controllers;

import br.com.branlycrm.catalog.dto.request.ActiveRequest;
import br.com.branlycrm.catalog.dto.request.ProcedureRequest;
import br.com.branlycrm.catalog.dto.response.ProcedureView;
import br.com.branlycrm.catalog.services.CatalogService;
import br.com.branlycrm.identity.security.CrmPrincipal;
import br.com.branlycrm.identity.security.SelectedClinic;
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

/** Procedures of the clinic selected in the session (CRM-034), returned whole. */
@Tag(name = "Procedures", description = "Procedures the clinic sells")
@SecurityRequirement(name = "session")
@RestController
@RequestMapping("/v1/clinic/procedures")
public final class ProcedureController {
  private final CatalogService catalog;

  public ProcedureController(CatalogService catalog) {
    this.catalog = catalog;
  }

  @Operation(
      operationId = "listProcedures",
      summary = "List procedures by name, active only unless includeInactive=true")
  @ApiResponse(responseCode = "200", description = "Procedures")
  @GetMapping
  public List<ProcedureView> list(
      @RequestParam(required = false) String includeInactive,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return catalog.listProcedures(
        SelectedClinic.require(session), principal.subjectId(), includeInactive);
  }

  @Operation(operationId = "createProcedure", summary = "Create a procedure (OWNER, MANAGER)")
  @ApiResponse(responseCode = "201", description = "Procedure created")
  @ApiResponse(responseCode = "409", description = "PROCEDURE_DUPLICATE, PROCEDURE_LIMIT")
  @SecurityRequirement(name = "csrf")
  @PostMapping
  public ResponseEntity<ProcedureView> create(
      @RequestBody ProcedureRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    ProcedureView created =
        catalog.createProcedure(SelectedClinic.require(session), principal.subjectId(), request);
    // The catalog is read as a list, so the 201 carries no Location.
    return ResponseEntity.status(201).body(created);
  }

  @Operation(operationId = "updateProcedure", summary = "Edit a procedure (OWNER, MANAGER)")
  @ApiResponse(responseCode = "200", description = "Procedure updated")
  @ApiResponse(responseCode = "404", description = "PROCEDURE_NOT_FOUND")
  @ApiResponse(responseCode = "409", description = "PROCEDURE_STALE, PROCEDURE_DUPLICATE")
  @SecurityRequirement(name = "csrf")
  @PutMapping("/{procedureId}")
  public ProcedureView update(
      @PathVariable UUID procedureId,
      @RequestBody ProcedureRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return catalog.updateProcedure(
        SelectedClinic.require(session), principal.subjectId(), procedureId, request);
  }

  @Operation(
      operationId = "setProcedureActive",
      summary = "Activate or deactivate a procedure (OWNER, MANAGER); idempotent")
  @ApiResponse(responseCode = "200", description = "Procedure")
  @ApiResponse(responseCode = "404", description = "PROCEDURE_NOT_FOUND")
  @SecurityRequirement(name = "csrf")
  @PutMapping("/{procedureId}/active")
  public ProcedureView setActive(
      @PathVariable UUID procedureId,
      @RequestBody ActiveRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return catalog.setProcedureActive(
        SelectedClinic.require(session), principal.subjectId(), procedureId, request);
  }
}
