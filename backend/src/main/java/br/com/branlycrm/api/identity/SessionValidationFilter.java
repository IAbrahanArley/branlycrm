package br.com.branlycrm.api.identity;
import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import br.com.branlycrm.api.tenancy.TenantAccessService;

public class SessionValidationFilter extends OncePerRequestFilter {
    public static final String SELECTED_TENANT = "SELECTED_TENANT_ID";
    private final IdentityService identities;
    private final TenantAccessService tenants;
    public SessionValidationFilter(IdentityService identities, TenantAccessService tenants) {
        this.identities = identities;
        this.tenants = tenants;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CrmPrincipal principal) {
            if (!identities.isCurrent(principal)) {
                var session = request.getSession(false);
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
                response.setStatus(401);
                return;
            }
            var session = request.getSession(false);
            if (session != null && session.getAttribute(SELECTED_TENANT) instanceof UUID tenantId) {
                try { tenants.authorize(tenantId, principal.subjectId()); }
                catch (AccessDeniedException denied) {
                    session.removeAttribute(SELECTED_TENANT);
                    response.setStatus(403);
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}