package br.com.branlycrm.identity.security;

import br.com.branlycrm.common.web.ApiErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Process-local throttling of login and of the anonymous invitation endpoints, counted per path and
 * client address. Replace with a shared limiter before running replicas (autenticacao.md).
 */
public class LoginRateLimitFilter extends OncePerRequestFilter {
  static final int MAX_ATTEMPTS = 20;
  static final long WINDOW_MILLIS = 60_000;
  static final int MAX_TRACKED_ADDRESSES = 10_000;

  private final Set<String> paths;
  private final Map<String, Window> attempts = new HashMap<>();

  public LoginRateLimitFilter(Set<String> paths) {
    this.paths = Set.copyOf(paths);
  }

  private record Window(long startsAt, int count) {}

  synchronized boolean allow(String address, long now) {
    attempts.entrySet().removeIf(entry -> now - entry.getValue().startsAt() >= WINDOW_MILLIS);
    Window current = attempts.get(address);
    if (current == null && attempts.size() >= MAX_TRACKED_ADDRESSES) return false;
    if (current == null) current = new Window(now, 0);
    if (current.count() >= MAX_ATTEMPTS) return false;
    attempts.put(address, new Window(current.startsAt(), current.count() + 1));
    return true;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getServletPath();
    if ("POST".equals(request.getMethod())
        && paths.contains(path)
        && !allow(path + " " + request.getRemoteAddr(), System.currentTimeMillis())) {
      response.setHeader("Retry-After", "60");
      ApiErrors.write(
          response, 429, ApiErrors.RATE_LIMITED, "Muitas tentativas. Aguarde um minuto.");
      return;
    }
    chain.doFilter(request, response);
  }
}
