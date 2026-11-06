package br.com.branlycrm.identity.controllers;

import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.common.dto.response.PageView;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.identity.dto.request.RoleChangeRequest;
import br.com.branlycrm.identity.security.CrmPrincipal;
import br.com.branlycrm.identity.security.SelectedClinic;
import br.com.branlycrm.tenant.domain.ClinicRole;
import br.com.branlycrm.tenant.domain.TeamQuery;
import br.com.branlycrm.tenant.dto.response.TeamMemberView;
import br.com.branlycrm.tenant.services.TeamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Team of the clinic selected in the session (docs/permissoes.md). */
@Tag(name = "Team", description = "Members and roles of the selected clinic")
@SecurityRequirement(name = "session")
@RestController
@RequestMapping("/v1/clinic/team")
public final class TeamController {
  private final TeamService team;

  public TeamController(TeamService team) {
    this.team = team;
  }

  @Operation(summary = "List members of the selected clinic (OWNER, MANAGER), paginated")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: page, pageSize, role or active")
  @ApiResponse(responseCode = "403", description = "CLINIC_ACCESS_DENIED: role without team access")
  @ApiResponse(responseCode = "409", description = "CLINIC_NOT_SELECTED")
  @GetMapping
  public PageView<TeamMemberView> list(
      @Parameter(description = "1-based page, default 1") @RequestParam(required = false)
          String page,
      @Parameter(description = "Items per page, 1 to 100, default 25")
          @RequestParam(required = false)
          String pageSize,
      @Parameter(description = "OWNER, MANAGER, RECEPTION, SALES or PROFESSIONAL")
          @RequestParam(required = false)
          String role,
      @Parameter(description = "true or false; omitted lists both") @RequestParam(required = false)
          String active,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return team.list(
        SelectedClinic.require(session),
        principal.subjectId(),
        TeamQuery.of(role, active, PageQuery.of(page, pageSize)));
  }

  @Operation(summary = "Change a member's role")
  @ApiResponse(responseCode = "400", description = "Unknown role")
  @ApiResponse(
      responseCode = "403",
      description = "CSRF, role without permission or own membership")
  @ApiResponse(responseCode = "404", description = "Not an active member of the selected clinic")
  @ApiResponse(responseCode = "409", description = "Last owner or no clinic selected")
  @SecurityRequirement(name = "csrf")
  @PutMapping("/{subjectId}/role")
  public TeamMemberView changeRole(
      @PathVariable UUID subjectId,
      @RequestBody RoleChangeRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return team.changeRole(
        SelectedClinic.require(session), principal.subjectId(), subjectId, role(request));
  }

  private static ClinicRole role(RoleChangeRequest request) {
    if (request == null || request.role() == null) throw new InvalidInputException("role");
    try {
      return ClinicRole.valueOf(request.role());
    } catch (IllegalArgumentException unknown) {
      throw new InvalidInputException("role");
    }
  }

  @Operation(summary = "Deactivate a member; history and authorship are kept")
  @ApiResponse(responseCode = "204", description = "Member deactivated")
  @ApiResponse(
      responseCode = "403",
      description = "CSRF, role without permission or own membership")
  @ApiResponse(responseCode = "404", description = "Not an active member of the selected clinic")
  @ApiResponse(responseCode = "409", description = "Last owner or no clinic selected")
  @SecurityRequirement(name = "csrf")
  @DeleteMapping("/{subjectId}")
  public ResponseEntity<Void> deactivate(
      @PathVariable UUID subjectId,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    team.deactivate(SelectedClinic.require(session), principal.subjectId(), subjectId);
    return ResponseEntity.noContent().build();
  }
}
