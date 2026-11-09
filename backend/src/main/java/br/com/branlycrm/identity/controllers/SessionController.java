package br.com.branlycrm.identity.controllers;

import br.com.branlycrm.identity.dto.response.ClinicSelectionView;
import br.com.branlycrm.identity.dto.response.ClinicView;
import br.com.branlycrm.identity.dto.response.CsrfView;
import br.com.branlycrm.identity.dto.response.CurrentClinicView;
import br.com.branlycrm.identity.dto.response.IdentityView;
import br.com.branlycrm.identity.security.CrmPrincipal;
import br.com.branlycrm.identity.security.SelectedClinic;
import br.com.branlycrm.identity.security.SessionValidationFilter;
import br.com.branlycrm.identity.services.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Login and logout are Spring Security filters, documented in OpenApiConfiguration. */
@Tag(name = "Session", description = "Authenticated identity and clinic selection")
@RestController
@RequestMapping("/v1/auth")
public final class SessionController {
  private final SessionService sessions;

  public SessionController(SessionService sessions) {
    this.sessions = sessions;
  }

  @Operation(summary = "Obtain the CSRF token before login or mutations")
  @GetMapping("/csrf")
  public CsrfView csrf(CsrfToken token) {
    return new CsrfView(token.getToken(), token.getHeaderName(), token.getParameterName());
  }

  @Operation(summary = "Authenticated identity, without credentials")
  @SecurityRequirement(name = "session")
  @GetMapping("/me")
  public IdentityView me(@AuthenticationPrincipal CrmPrincipal principal) {
    return sessions.identity(principal);
  }

  @Operation(summary = "Active memberships in active clinics")
  @SecurityRequirement(name = "session")
  @GetMapping("/clinics")
  public List<ClinicView> clinics(@AuthenticationPrincipal CrmPrincipal principal) {
    return sessions.clinics(principal);
  }

  @Operation(summary = "Selected clinic with role and permission scopes (UI hint only)")
  @ApiResponse(responseCode = "200", description = "Selected clinic, role and permission scopes")
  @ApiResponse(responseCode = "403", description = "CLINIC_ACCESS_DENIED")
  @ApiResponse(responseCode = "409", description = "CLINIC_NOT_SELECTED")
  @SecurityRequirement(name = "session")
  @GetMapping("/clinic")
  public CurrentClinicView current(
      @AuthenticationPrincipal CrmPrincipal principal, HttpSession session) {
    return sessions.current(principal, SelectedClinic.require(session));
  }

  @Operation(summary = "Select a clinic after validating the membership")
  @ApiResponse(responseCode = "200", description = "Clinic selected for this session")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: tenantId")
  @ApiResponse(responseCode = "403", description = "CLINIC_ACCESS_DENIED")
  @SecurityRequirement(name = "session")
  @SecurityRequirement(name = "csrf")
  @PutMapping("/clinic/{tenantId}")
  public ClinicSelectionView select(
      @PathVariable UUID tenantId,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    ClinicSelectionView selection = sessions.select(principal, tenantId);
    session.setAttribute(SessionValidationFilter.SELECTED_TENANT, selection.tenantId());
    return selection;
  }
}
