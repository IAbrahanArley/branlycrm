package br.com.branlycrm.onboarding.controllers;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.identity.security.CrmPrincipal;
import br.com.branlycrm.onboarding.domain.NewAccount;
import br.com.branlycrm.onboarding.dto.request.InvitationAcceptRequest;
import br.com.branlycrm.onboarding.dto.request.InvitationTokenRequest;
import br.com.branlycrm.onboarding.dto.response.InvitationPreviewView;
import br.com.branlycrm.onboarding.services.InvitationAcceptanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public invitation endpoints (ADR-028). Anonymous, but protected by CSRF and the request limiter.
 * The token travels in the body; the frontend reads it from the link fragment. Accepting never logs
 * in: the person logs in afterwards with the usual filter.
 */
@Tag(name = "Invitations", description = "Preview and accept an invitation")
// Only the HTTP process: the provisioner has no password encoder nor mail.
@ConditionalOnWebApplication
@RestController
@RequestMapping("/v1/invitations")
public final class InvitationController {
  private final InvitationAcceptanceService acceptance;

  public InvitationController(InvitationAcceptanceService acceptance) {
    this.acceptance = acceptance;
  }

  @Operation(summary = "What an invitation grants, for its token holder")
  @ApiResponse(responseCode = "200", description = "Kind, target name, role and expiry")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: body")
  @ApiResponse(
      responseCode = "404",
      description = "INVITATION_INVALID: unknown, expired, used or revoked")
  @ApiResponse(responseCode = "429", description = "RATE_LIMITED")
  @SecurityRequirement(name = "csrf")
  @PostMapping("/preview")
  public InvitationPreviewView preview(@RequestBody InvitationTokenRequest request) {
    return acceptance.preview(request == null ? null : request.token());
  }

  @Operation(summary = "Accept an invitation; creates the account when none exists")
  @ApiResponse(responseCode = "204", description = "Membership created or reactivated")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: displayName, password or body")
  @ApiResponse(
      responseCode = "404",
      description = "INVITATION_INVALID: unknown, expired, used, revoked or no longer authorized")
  @ApiResponse(
      responseCode = "409",
      description =
          "INVITATION_LOGIN_REQUIRED, INVITATION_ACCOUNT_MISMATCH or MEMBER_ALREADY_ACTIVE")
  @ApiResponse(responseCode = "429", description = "RATE_LIMITED")
  @SecurityRequirement(name = "csrf")
  @PostMapping("/accept")
  public ResponseEntity<Void> accept(
      @RequestBody InvitationAcceptRequest request,
      @AuthenticationPrincipal CrmPrincipal principal) {
    if (request == null) throw new InvalidInputException("body");
    Optional<UUID> session = Optional.ofNullable(principal).map(CrmPrincipal::subjectId);
    NewAccount newAccount =
        session.isEmpty() && (request.displayName() != null || request.password() != null)
            ? new NewAccount(request.displayName(), request.password())
            : null;
    acceptance.accept(request.token(), session, newAccount);
    return ResponseEntity.noContent().build();
  }
}
