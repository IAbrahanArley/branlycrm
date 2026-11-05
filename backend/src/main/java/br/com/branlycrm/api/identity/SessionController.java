package br.com.branlycrm.api.identity;

import java.util.List;
import java.util.UUID;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import br.com.branlycrm.api.tenancy.TenantRegistryRepository;

@Tag(name = "Session", description = "Authenticated identity and clinic selection")
@RequestMapping("/v1/auth")
interface SessionControllerDoc {
    @Operation(summary = "Obtain CSRF token before login or mutations")
    @GetMapping("/csrf")
    CsrfToken csrf(CsrfToken token);

    @Operation(summary = "Get authenticated identity, without credentials")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @GetMapping("/me")
    IdentityResponse me(@AuthenticationPrincipal CrmPrincipal principal);

    @Operation(summary = "List active clinic memberships")
    @GetMapping("/clinics")
    List<TenantRegistryRepository.Membership> clinics(@AuthenticationPrincipal CrmPrincipal principal);

    @Operation(summary = "Select a clinic after validating membership")
    @ApiResponse(responseCode = "403", description = "CSRF or clinic access denied")
    @PutMapping("/clinic/{tenantId}")
    SelectionResponse select(@PathVariable("tenantId") UUID tenantId,
        @AuthenticationPrincipal CrmPrincipal principal, HttpSession session);
}

@RestController
public class SessionController implements SessionControllerDoc {
    private final SessionService service;
    public SessionController(SessionService service) { this.service = service; }
    @Override public CsrfToken csrf(CsrfToken token) { return token; }
    @Override public IdentityResponse me(CrmPrincipal principal) {
        return new IdentityResponse(principal.subjectId(), principal.getUsername());
    }
    @Override public List<TenantRegistryRepository.Membership> clinics(CrmPrincipal principal) {
        return service.memberships(principal);
    }
    @Override public SelectionResponse select(UUID tenantId, CrmPrincipal principal, HttpSession session) {
        service.authorizeSelection(principal, tenantId);
        session.setAttribute(SessionValidationFilter.SELECTED_TENANT, tenantId);
        return new SelectionResponse(tenantId);
    }


}