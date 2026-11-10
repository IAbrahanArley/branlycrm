package br.com.branlycrm.catalog.controllers;

import br.com.branlycrm.catalog.dto.request.ActiveRequest;
import br.com.branlycrm.catalog.dto.request.ProfessionalRequest;
import br.com.branlycrm.catalog.dto.response.ProfessionalView;
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

/** Professionals of the clinic selected in the session (CRM-034), without login, returned whole. */
@Tag(name = "Professionals", description = "Professionals who perform the clinic's procedures")
@SecurityRequirement(name = "session")
@RestController
@RequestMapping("/v1/clinic/professionals")
public final class ProfessionalController {
  private final CatalogService catalog;

  public ProfessionalController(CatalogService catalog) {
    this.catalog = catalog;
  }

  @Operation(
      operationId = "listProfessionals",
      summary = "List professionals by name, active only unless includeInactive=true")
  @ApiResponse(responseCode = "200", description = "Professionals")
  @GetMapping
  public List<ProfessionalView> list(
      @RequestParam(required = false) String includeInactive,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return catalog.listProfessionals(
        SelectedClinic.require(session), principal.subjectId(), includeInactive);
  }

  @Operation(operationId = "createProfessional", summary = "Add a professional (OWNER, MANAGER)")
  @ApiResponse(responseCode = "201", description = "Professional added")
  @ApiResponse(responseCode = "409", description = "PROFESSIONAL_DUPLICATE, PROFESSIONAL_LIMIT")
  @SecurityRequirement(name = "csrf")
  @PostMapping
  public ResponseEntity<ProfessionalView> create(
      @RequestBody ProfessionalRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    ProfessionalView created =
        catalog.createProfessional(SelectedClinic.require(session), principal.subjectId(), request);
    // The catalog is read as a list, so the 201 carries no Location.
    return ResponseEntity.status(201).body(created);
  }

  @Operation(operationId = "renameProfessional", summary = "Rename a professional (OWNER, MANAGER)")
  @ApiResponse(responseCode = "200", description = "Professional renamed")
  @ApiResponse(responseCode = "404", description = "PROFESSIONAL_NOT_FOUND")
  @ApiResponse(responseCode = "409", description = "PROFESSIONAL_STALE, PROFESSIONAL_DUPLICATE")
  @SecurityRequirement(name = "csrf")
  @PutMapping("/{professionalId}")
  public ProfessionalView rename(
      @PathVariable UUID professionalId,
      @RequestBody ProfessionalRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return catalog.renameProfessional(
        SelectedClinic.require(session), principal.subjectId(), professionalId, request);
  }

  @Operation(
      operationId = "setProfessionalActive",
      summary = "Activate or deactivate a professional (OWNER, MANAGER); idempotent")
  @ApiResponse(responseCode = "200", description = "Professional")
  @ApiResponse(responseCode = "404", description = "PROFESSIONAL_NOT_FOUND")
  @SecurityRequirement(name = "csrf")
  @PutMapping("/{professionalId}/active")
  public ProfessionalView setActive(
      @PathVariable UUID professionalId,
      @RequestBody ActiveRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return catalog.setProfessionalActive(
        SelectedClinic.require(session), principal.subjectId(), professionalId, request);
  }
}
