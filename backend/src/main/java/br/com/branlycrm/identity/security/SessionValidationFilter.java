package br.com.branlycrm.identity.security;

import br.com.branlycrm.common.controllers.ApiErrors;
import br.com.branlycrm.identity.services.IdentityService;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.services.TenantAccessService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Revalidates the account and the selected clinic on every authenticated request, so revocation
 * takes effect on the next call instead of at session expiry.
 */
public class SessionValidationFilter extends OncePerRequestFilter {
  public static final String SELECTED_TENANT = "SELECTED_TENANT_ID";

  private final IdentityService identities;
  private final TenantAccessService tenants;

  public SessionValidationFilter(IdentityService identities, TenantAccessService tenants) {
    this.identities = identities;
    this.tenants = tenants;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof CrmPrincipal principal) {
      var session = request.getSession(false);
      if (!identities.isCurrent(principal)) {
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        ApiErrors.write(response, 401, ApiErrors.SESSION_REVOKED, "Sessão encerrada.");
        return;
      }
      if (session != null && session.getAttribute(SELECTED_TENANT) instanceof UUID tenantId) {
        try {
          tenants.authorize(tenantId, principal.subjectId());
        } catch (TenantAccessException denied) {
          session.removeAttribute(SELECTED_TENANT);
          ApiErrors.write(
              response, 403, ApiErrors.CLINIC_ACCESS_DENIED, "Acesso à clínica negado.");
          return;
        }
      }
    }
    chain.doFilter(request, response);
  }
}
