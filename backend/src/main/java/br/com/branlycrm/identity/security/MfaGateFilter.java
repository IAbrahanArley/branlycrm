package br.com.branlycrm.identity.security;

import br.com.branlycrm.common.web.ApiErrors;
import br.com.branlycrm.identity.mfa.domain.MfaProof;
import br.com.branlycrm.identity.mfa.domain.MfaState;
import br.com.branlycrm.identity.mfa.services.MfaService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Second-factor gate (CRM-016), right after {@link SessionValidationFilter}. Acts only on sessions
 * with a {@link CrmPrincipal}, including on the anonymous invitation routes, and denies by default:
 * each state reaches only the exact method and servlet path pairs listed here. The state is derived
 * on every request from the proof in the session, never stored as a flag.
 */
public class MfaGateFilter extends OncePerRequestFilter {
  /** {@link MfaProof} of this session, written by verify and confirm, removed by login. */
  public static final String MFA_PROOF = "MFA_PROOF";

  /** Instant of the password login of this session. */
  public static final String PASSWORD_AUTHENTICATED_AT = "PASSWORD_AUTHENTICATED_AT";

  /** Wrong codes in this session (MfaErrorHandler). */
  public static final String MFA_FAILURES = "MFA_FAILURES";

  /** Nonce of the secret staged by this session's setup; confirm accepts only that one. */
  public static final String MFA_SETUP_NONCE = "MFA_SETUP_NONCE";

  /** A session waiting for the code longer than this is dropped. */
  public static final Duration PENDING_LIMIT = Duration.ofMinutes(5);

  public static final String VERIFY_PATH = "/v1/auth/mfa/verify";

  private static final Set<String> ALWAYS =
      Set.of(
          "GET /v1/auth/csrf",
          "POST /v1/auth/logout",
          "GET /v1/auth/me",
          "GET /v1/auth/mfa",
          "GET /actuator/health");
  private static final Set<String> PENDING = union(ALWAYS, "POST " + VERIFY_PATH);
  private static final Set<String> ENROLLMENT =
      union(ALWAYS, "POST /v1/auth/mfa/totp/setup", "POST /v1/auth/mfa/totp/confirm");
  private static final Set<String> COMPLETE_DENIED = Set.of("POST " + VERIFY_PATH);

  private final MfaService mfa;
  private final Clock clock;

  public MfaGateFilter(MfaService mfa, Clock clock) {
    this.mfa = mfa;
    this.clock = clock;
  }

  /** Whether a session in this state may reach the route (also used by the OpenAPI document). */
  public static boolean allows(MfaState state, String method, String servletPath) {
    String route = method + " " + servletPath;
    return switch (state) {
      case MFA_PENDING -> PENDING.contains(route);
      case ENROLLMENT_REQUIRED -> ENROLLMENT.contains(route);
      case COMPLETE -> !COMPLETE_DENIED.contains(route);
    };
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !(authentication.getPrincipal() instanceof CrmPrincipal principal)) {
      chain.doFilter(request, response);
      return;
    }
    HttpSession session = request.getSession(false);
    MfaProof proof =
        session != null && session.getAttribute(MFA_PROOF) instanceof MfaProof stored
            ? stored
            : null;
    MfaState state = mfa.state(principal, proof);
    if (state == MfaState.MFA_PENDING && expired(session)) {
      if (session != null) session.invalidate();
      SecurityContextHolder.clearContext();
      ApiErrors.write(response, 401, ApiErrors.SESSION_REVOKED, "Sessão encerrada.");
      return;
    }
    if (!allows(state, request.getMethod(), request.getServletPath())) {
      switch (state) {
        case MFA_PENDING ->
            ApiErrors.write(
                response, 401, ApiErrors.MFA_REQUIRED, "Informe o código do segundo fator.");
        case ENROLLMENT_REQUIRED ->
            ApiErrors.write(
                response,
                403,
                ApiErrors.MFA_ENROLLMENT_REQUIRED,
                "Cadastre o segundo fator para continuar.");
        case COMPLETE ->
            ApiErrors.write(
                response, 409, ApiErrors.MFA_ALREADY_COMPLETE, "O segundo fator já foi validado.");
      }
      return;
    }
    chain.doFilter(request, response);
  }

  private boolean expired(HttpSession session) {
    if (session == null
        || !(session.getAttribute(PASSWORD_AUTHENTICATED_AT) instanceof Instant loggedInAt)) {
      return true;
    }
    return clock.instant().isAfter(loggedInAt.plus(PENDING_LIMIT));
  }

  private static Set<String> union(Set<String> base, String... more) {
    Set<String> all = new HashSet<>(base);
    all.addAll(Set.of(more));
    return Set.copyOf(all);
  }
}
