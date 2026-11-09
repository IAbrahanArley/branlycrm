package br.com.branlycrm.identity.mfa.controllers;

import br.com.branlycrm.identity.mfa.domain.MfaEnrollment;
import br.com.branlycrm.identity.mfa.domain.MfaProof;
import br.com.branlycrm.identity.mfa.domain.MfaSetup;
import br.com.branlycrm.identity.mfa.dto.request.MfaCodeRequest;
import br.com.branlycrm.identity.mfa.dto.request.MfaVerifyRequest;
import br.com.branlycrm.identity.mfa.dto.response.MfaSetupView;
import br.com.branlycrm.identity.mfa.dto.response.MfaStatusView;
import br.com.branlycrm.identity.mfa.dto.response.RecoveryCodesView;
import br.com.branlycrm.identity.mfa.services.MfaService;
import br.com.branlycrm.identity.security.CrmPrincipal;
import br.com.branlycrm.identity.security.MfaGateFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TOTP second factor (CRM-016). Login stays 204; the session then reads its state here. Which state
 * reaches which route is decided by {@link MfaGateFilter} before this controller runs.
 */
@Tag(name = "Second factor", description = "TOTP enrollment, verification and recovery codes")
@ConditionalOnWebApplication
@RestController
@RequestMapping("/v1/auth/mfa")
public final class MfaController {
  private final MfaService mfa;

  public MfaController(MfaService mfa) {
    this.mfa = mfa;
  }

  @Operation(
      summary = "Second-factor state of the session",
      description =
          "state: MFA_PENDING (send a code to /verify), ENROLLMENT_REQUIRED (set up a factor) or"
              + " COMPLETE. Allowed in every state.")
  @SecurityRequirement(name = "session")
  @GetMapping
  public MfaStatusView status(
      @AuthenticationPrincipal CrmPrincipal principal, HttpServletRequest request) {
    return mfa.status(principal, proof(request.getSession(false)));
  }

  @Operation(
      summary = "Prove the second factor with a TOTP code or one recovery code",
      description =
          "Only in MFA_PENDING. Renews the session id. Five wrong codes in a session end it; ten"
              + " for the account lock it for 15 minutes.")
  @ApiResponse(responseCode = "204", description = "Second factor proved; session COMPLETE")
  @ApiResponse(responseCode = "400", description = "MFA_CODE_INVALID; INVALID_INPUT: code, body")
  @ApiResponse(
      responseCode = "401",
      description = "SESSION_REVOKED after five wrong codes in the session")
  @ApiResponse(responseCode = "409", description = "MFA_ALREADY_COMPLETE")
  @ApiResponse(responseCode = "429", description = "MFA_LOCKED or RATE_LIMITED; honor Retry-After")
  @SecurityRequirement(name = "session")
  @SecurityRequirement(name = "csrf")
  @PostMapping("/verify")
  public ResponseEntity<Void> verify(
      @RequestBody MfaVerifyRequest body,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpServletRequest request) {
    MfaProof proof =
        mfa.verify(
            principal,
            body == null ? null : body.code(),
            body == null ? null : body.recoveryCode());
    prove(request, proof);
    return ResponseEntity.noContent().build();
  }

  @Operation(
      summary = "Start enrolling a TOTP authenticator",
      description =
          "Returns the secret and otpauth URI once; the pending secret expires in 10 minutes and"
              + " only this session can confirm it. Replacing a confirmed factor (COMPLETE) requires"
              + " its current code in the body; the old factor keeps working until confirmation.")
  @ApiResponse(responseCode = "200", description = "Pending secret staged")
  @ApiResponse(responseCode = "400", description = "MFA_CODE_INVALID; INVALID_INPUT: code, body")
  @ApiResponse(responseCode = "401", description = "SESSION_REVOKED after five wrong codes")
  @ApiResponse(responseCode = "429", description = "MFA_LOCKED; honor Retry-After")
  @SecurityRequirement(name = "session")
  @SecurityRequirement(name = "csrf")
  @PostMapping("/totp/setup")
  public ResponseEntity<MfaSetupView> setup(
      @RequestBody(required = false) MfaCodeRequest body,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpServletRequest request) {
    MfaSetup setup = mfa.setup(principal, body == null ? null : body.code());
    request.getSession().setAttribute(MfaGateFilter.MFA_SETUP_NONCE, setup.pendingNonce());
    return secret(new MfaSetupView(setup.secret(), setup.otpauthUri()));
  }

  @Operation(
      summary = "Confirm the pending TOTP secret with a code from it",
      description =
          "Returns ten recovery codes once and completes the session. Replacing a confirmed factor"
              + " ends every session of the account, this one included: log in again.")
  @ApiResponse(responseCode = "200", description = "Factor active; recovery codes shown once")
  @ApiResponse(responseCode = "400", description = "MFA_CODE_INVALID; INVALID_INPUT: code, body")
  @ApiResponse(responseCode = "401", description = "SESSION_REVOKED after five wrong codes")
  @ApiResponse(
      responseCode = "409",
      description = "MFA_SETUP_EXPIRED: no setup of this session, expired or replaced")
  @ApiResponse(responseCode = "429", description = "MFA_LOCKED; honor Retry-After")
  @SecurityRequirement(name = "session")
  @SecurityRequirement(name = "csrf")
  @PostMapping("/totp/confirm")
  public ResponseEntity<RecoveryCodesView> confirm(
      @RequestBody MfaCodeRequest body,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    byte[] nonce =
        session != null && session.getAttribute(MfaGateFilter.MFA_SETUP_NONCE) instanceof byte[] n
            ? n
            : null;
    MfaEnrollment enrollment = mfa.confirm(principal, body == null ? null : body.code(), nonce);
    request.getSession().removeAttribute(MfaGateFilter.MFA_SETUP_NONCE);
    // A replacement bumped auth_version: this session ends on its next request anyway.
    if (!enrollment.replaced()) prove(request, enrollment.proof());
    return secret(new RecoveryCodesView(enrollment.recoveryCodes()));
  }

  @Operation(
      summary = "Replace the recovery codes",
      description = "Needs the current TOTP code; the previous codes stop working.")
  @ApiResponse(responseCode = "200", description = "Ten new recovery codes, shown once")
  @ApiResponse(responseCode = "400", description = "MFA_CODE_INVALID; INVALID_INPUT: code, body")
  @ApiResponse(responseCode = "401", description = "SESSION_REVOKED after five wrong codes")
  @ApiResponse(responseCode = "409", description = "MFA_NOT_ENROLLED")
  @ApiResponse(responseCode = "429", description = "MFA_LOCKED; honor Retry-After")
  @SecurityRequirement(name = "session")
  @SecurityRequirement(name = "csrf")
  @PostMapping("/recovery-codes")
  public ResponseEntity<RecoveryCodesView> regenerate(
      @RequestBody MfaCodeRequest body, @AuthenticationPrincipal CrmPrincipal principal) {
    return secret(
        new RecoveryCodesView(
            mfa.regenerateRecoveryCodes(principal, body == null ? null : body.code())));
  }

  @Operation(
      summary = "Turn off an optional TOTP factor",
      description =
          "Needs the current code. Owners and agency admins must keep it. Ends every session of"
              + " the account.")
  @ApiResponse(responseCode = "204", description = "Factor removed; every session ended")
  @ApiResponse(responseCode = "400", description = "MFA_CODE_INVALID; INVALID_INPUT: code, body")
  @ApiResponse(responseCode = "401", description = "SESSION_REVOKED after five wrong codes")
  @ApiResponse(responseCode = "403", description = "MFA_MANDATORY")
  @ApiResponse(responseCode = "409", description = "MFA_NOT_ENROLLED")
  @ApiResponse(responseCode = "429", description = "MFA_LOCKED; honor Retry-After")
  @SecurityRequirement(name = "session")
  @SecurityRequirement(name = "csrf")
  @DeleteMapping("/totp")
  public ResponseEntity<Void> disable(
      @RequestBody MfaCodeRequest body, @AuthenticationPrincipal CrmPrincipal principal) {
    mfa.disable(principal, body == null ? null : body.code());
    return ResponseEntity.noContent().build();
  }

  /** Secrets and codes shown once: never kept by a browser or proxy cache. */
  private static <T> ResponseEntity<T> secret(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }

  /** Session fixation: a new id once the second factor is proved. */
  private static void prove(HttpServletRequest request, MfaProof proof) {
    request.changeSessionId();
    HttpSession session = request.getSession();
    session.setAttribute(MfaGateFilter.MFA_PROOF, proof);
    session.removeAttribute(MfaGateFilter.MFA_FAILURES);
  }

  private static MfaProof proof(HttpSession session) {
    return session != null && session.getAttribute(MfaGateFilter.MFA_PROOF) instanceof MfaProof p
        ? p
        : null;
  }
}
