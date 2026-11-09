package br.com.branlycrm.onboarding.controllers;

import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.common.dto.response.PageView;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.identity.security.CrmPrincipal;
import br.com.branlycrm.identity.security.SelectedClinic;
import br.com.branlycrm.onboarding.domain.InvitationEmail;
import br.com.branlycrm.onboarding.dto.request.TeamInvitationRequest;
import br.com.branlycrm.onboarding.dto.response.IssuedInvitationView;
import br.com.branlycrm.onboarding.dto.response.TeamInvitationView;
import br.com.branlycrm.onboarding.services.TeamInvitationService;
import br.com.branlycrm.tenant.domain.ClinicRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Invitations to the clinic selected in the session (docs/permissoes.md, ADR-028). */
@Tag(name = "Team invitations", description = "Invite, resend and revoke for the selected clinic")
@SecurityRequirement(name = "session")
// Only the HTTP process: the provisioner has no password encoder nor mail.
@ConditionalOnWebApplication
@RestController
@RequestMapping("/v1/clinic/team/invitations")
public final class TeamInvitationController {
  private final TeamInvitationService invitations;

  public TeamInvitationController(TeamInvitationService invitations) {
    this.invitations = invitations;
  }

  @Operation(summary = "Pending invitations of the selected clinic (OWNER, MANAGER), paginated")
  @ApiResponse(responseCode = "200", description = "Page of pending invitations")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: page or pageSize")
  @ApiResponse(responseCode = "403", description = "CLINIC_ACCESS_DENIED")
  @ApiResponse(responseCode = "409", description = "CLINIC_NOT_SELECTED")
  @GetMapping
  public PageView<TeamInvitationView> list(
      @Parameter(description = "1-based page, default 1") @RequestParam(required = false)
          String page,
      @Parameter(description = "Items per page, 1 to 100, default 25")
          @RequestParam(required = false)
          String pageSize,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return invitations.list(
        SelectedClinic.require(session), principal.subjectId(), PageQuery.of(page, pageSize));
  }

  @Operation(summary = "Invite an address, or reissue its pending invitation")
  @ApiResponse(responseCode = "201", description = "Invitation issued; delivery SENT or FAILED")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: email, role or body")
  @ApiResponse(
      responseCode = "403",
      description = "CLINIC_ACCESS_DENIED or INVITATION_FORBIDDEN (role beyond the actor's reach)")
  @ApiResponse(responseCode = "409", description = "MEMBER_ALREADY_ACTIVE or CLINIC_NOT_SELECTED")
  @SecurityRequirement(name = "csrf")
  @PostMapping
  public ResponseEntity<IssuedInvitationView> invite(
      @RequestBody TeamInvitationRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    if (request == null) throw new InvalidInputException("body");
    IssuedInvitationView issued =
        invitations.invite(
            SelectedClinic.require(session),
            principal.subjectId(),
            InvitationEmail.of(request.email()),
            role(request.role()));
    return ResponseEntity.status(201).body(issued);
  }

  @Operation(summary = "Resend a pending invitation with a new token and expiry")
  @ApiResponse(responseCode = "200", description = "Invitation reissued; delivery SENT or FAILED")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: invitationId")
  @ApiResponse(responseCode = "403", description = "CLINIC_ACCESS_DENIED or INVITATION_FORBIDDEN")
  @ApiResponse(
      responseCode = "404",
      description = "INVITATION_NOT_FOUND: not a pending invitation of the selected clinic")
  @ApiResponse(responseCode = "409", description = "CLINIC_NOT_SELECTED")
  @SecurityRequirement(name = "csrf")
  @PostMapping("/{invitationId}/resend")
  public IssuedInvitationView resend(
      @PathVariable UUID invitationId,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return invitations.resend(SelectedClinic.require(session), principal.subjectId(), invitationId);
  }

  @Operation(summary = "Revoke a pending invitation")
  @ApiResponse(responseCode = "204", description = "Invitation revoked")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: invitationId")
  @ApiResponse(responseCode = "403", description = "CLINIC_ACCESS_DENIED or INVITATION_FORBIDDEN")
  @ApiResponse(
      responseCode = "404",
      description = "INVITATION_NOT_FOUND: not a pending invitation of the selected clinic")
  @ApiResponse(responseCode = "409", description = "CLINIC_NOT_SELECTED")
  @SecurityRequirement(name = "csrf")
  @DeleteMapping("/{invitationId}")
  public ResponseEntity<Void> revoke(
      @PathVariable UUID invitationId,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    invitations.revoke(SelectedClinic.require(session), principal.subjectId(), invitationId);
    return ResponseEntity.noContent().build();
  }

  private static ClinicRole role(String value) {
    if (value == null) throw new InvalidInputException("role");
    try {
      return ClinicRole.valueOf(value);
    } catch (IllegalArgumentException unknown) {
      throw new InvalidInputException("role");
    }
  }
}
