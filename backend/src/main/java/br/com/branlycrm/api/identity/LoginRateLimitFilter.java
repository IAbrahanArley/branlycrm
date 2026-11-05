package br.com.branlycrm.api.identity;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

// Single-instance protection. Replace with a shared limiter before scaling replicas.
public class LoginRateLimitFilter extends OncePerRequestFilter {
    private final Map<String, Window> attempts = new HashMap<>();
    private record Window(long startsAt, int count) {}
    synchronized boolean allow(String address, long now) {
        attempts.entrySet().removeIf(entry -> now - entry.getValue().startsAt() >= 60_000);
        Window current = attempts.get(address);
        if (current == null && attempts.size() >= 10_000) return false;
        if (current == null) current = new Window(now, 0);
        if (current.count() >= 20) return false;
        attempts.put(address, new Window(current.startsAt(), current.count() + 1));
        return true;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if ("POST".equals(request.getMethod()) && "/v1/auth/login".equals(request.getServletPath())
            && !allow(request.getRemoteAddr(), System.currentTimeMillis())) {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            return;
        }
        chain.doFilter(request, response);
    }
}