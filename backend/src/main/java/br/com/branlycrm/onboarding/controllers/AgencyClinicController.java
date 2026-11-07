package br.com.branlycrm.onboarding.controllers;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.identity.security.CrmPrincipal;
import br.com.branlycrm.onboarding.domain.ClinicName;
import br.com.branlycrm.onboarding.domain.InvitationEmail;
import br.com.branlycrm.onboarding.dto.request.AgencyClinicRequest;
import br.com.branlycrm.onboarding.dto.request.OwnerInvitationRequest;
import br.com.branlycrm.onboarding.dto.response.AgencyClinicView;
import br.com.branlycrm.onboarding.dto.response.IssuedInvitationView;
import br.com.branlycrm.onboarding.services.AgencyClinicService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Clinics an agency creates in its portfolio and their first-owner invitation (ADR-028). */
@Tag(name = "Agencies", description = "Partner agencies and their clinic portfolio")
@SecurityRequirement(name = "session")
// Only the HTTP process: the provisioner has no mail.
@ConditionalOnWebApplication
@RestController
@RequestMapping("/v1/agencies/{agencyId}/clinics")
public final class AgencyClinicController {
  private final AgencyClinicService clinics;

  public AgencyClinicController(AgencyClinicService clinics) {
    this.clinics = clinics;
  }

  @Operation(summary = "Create a clinic in the portfolio and invite its first owner")
  @ApiResponse(responseCode = "201", description = "Clinic created; it starts PROVISIONING")
  @ApiResponse(responseCode = "200", description = "Same requestId already created this clinic")
  @ApiResponse(
      responseCode = "400",
      description = "INVALID_INPUT: agencyId, requestId, clinicName, ownerEmail or body")
  @ApiResponse(responseCode = "404", description = "AGENCY_NOT_FOUND")
  @ApiResponse(
      responseCode = "409",
      description = "IDEMPOTENCY_KEY_REUSED or AGENCY_PENDING_CLINIC_LIMIT")
  @SecurityRequirement(name = "csrf")
  @PostMapping
  public ResponseEntity<AgencyClinicView> create(
      @PathVariable UUID agencyId,
      @RequestBody AgencyClinicRequest request,
      @AuthenticationPrincipal CrmPrincipal principal) {
    if (request == null) throw new InvalidInputException("body");
    AgencyClinicView view =
        clinics.create(
            agencyId,
            principal.subjectId(),
            requestId(request.requestId()),
            new ClinicName(request.clinicName()),
            InvitationEmail.of(request.ownerEmail()));
    return ResponseEntity.status(view.created() ? 201 : 200).body(view);
  }

  @Operation(summary = "Reissue the first-owner invitation while the clinic never had an owner")
  @ApiResponse(responseCode = "200", description = "Invitation issued; delivery SENT or FAILED")
  @ApiResponse(
      responseCode = "400",
      description = "INVALID_INPUT: agencyId, tenantId, ownerEmail or body")
  @ApiResponse(responseCode = "404", description = "AGENCY_NOT_FOUND or CLINIC_NOT_FOUND")
  @ApiResponse(responseCode = "409", description = "AGENCY_CLINIC_HAS_OWNER")
  @ApiResponse(responseCode = "429", description = "INVITATION_SEND_LIMIT")
  @SecurityRequirement(name = "csrf")
  @PostMapping("/{tenantId}/owner-invitation")
  public IssuedInvitationView reissueOwnerInvitation(
      @PathVariable UUID agencyId,
      @PathVariable UUID tenantId,
      @RequestBody OwnerInvitationRequest request,
      @AuthenticationPrincipal CrmPrincipal principal) {
    if (request == null) throw new InvalidInputException("body");
    return clinics.reissueOwnerInvitation(
        agencyId, principal.subjectId(), tenantId, InvitationEmail.of(request.ownerEmail()));
  }

  private static UUID requestId(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw new InvalidInputException("requestId");
    }
  }
}
