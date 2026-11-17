package br.com.branlycrm.config;

import br.com.branlycrm.common.web.ApiErrors;
import br.com.branlycrm.identity.mfa.services.MfaService;
import br.com.branlycrm.identity.security.CrmPrincipal;
import br.com.branlycrm.identity.security.LoginRateLimitFilter;
import br.com.branlycrm.identity.security.MfaGateFilter;
import br.com.branlycrm.identity.security.SessionValidationFilter;
import br.com.branlycrm.identity.services.AccountNoticeService;
import br.com.branlycrm.identity.services.IdentityService;
import br.com.branlycrm.tenant.services.TenantAccessService;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfException;

/**
 * ADR-015: server session with HttpOnly cookie and CSRF. Login and logout are handled by the native
 * Spring Security filters and answer with plain status codes, never redirects. Login stays 204 with
 * or without a second factor (CRM-016): {@link MfaGateFilter} then confines the session to the
 * second-factor routes until it is proved or enrolled.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class SecurityConfiguration {
  public static final String SESSION_COOKIE = "BRANLY_SESSION";
  public static final String LOGIN_PATH = "/v1/auth/login";
  public static final String LOGOUT_PATH = "/v1/auth/logout";
  public static final String INVITATION_PREVIEW_PATH = "/v1/invitations/preview";
  public static final String INVITATION_ACCEPT_PATH = "/v1/invitations/accept";

  /** Public WhatsApp Cloud API webhook (ADR-036), authenticated only by its signature. */
  public static final String WHATSAPP_WEBHOOK_PATH = "/v1/webhooks/whatsapp";

  /**
   * The webhook's own chain, matched before the session chain: no session, cookie, CSRF nor request
   * cache, and nothing else is reachable through it. The signature is checked by the controller
   * before the body is parsed.
   */
  @Bean
  @Order(1)
  SecurityFilterChain whatsappWebhookFilterChain(HttpSecurity http) throws Exception {
    return http.securityMatcher(WHATSAPP_WEBHOOK_PATH)
        .csrf(csrf -> csrf.disable())
        .requestCache(cache -> cache.disable())
        .securityContext(context -> context.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .anonymous(Customizer.withDefaults())
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.GET, WHATSAPP_WEBHOOK_PATH)
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, WHATSAPP_WEBHOOK_PATH)
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .exceptionHandling(
            // Anonymous denials (other methods) go to the entry point: same 403 body.
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, error) ->
                            ApiErrors.write(
                                response, 403, ApiErrors.ACCESS_DENIED, "Acesso negado."))
                    .accessDeniedHandler(
                        (request, response, error) ->
                            ApiErrors.write(
                                response, 403, ApiErrors.ACCESS_DENIED, "Acesso negado.")))
        .build();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  /**
   * Inactive or unverified accounts are refused after the password comparison, never before, so
   * they take as long as a wrong password and as an unknown e-mail (Spring hashes a dummy password
   * for unknown users). All three answer INVALID_CREDENTIALS.
   */
  @Bean
  DaoAuthenticationProvider authenticationProvider(
      IdentityService identities, PasswordEncoder passwordEncoder) {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(identities);
    provider.setPasswordEncoder(passwordEncoder);
    provider.setPostAuthenticationChecks(
        user -> {
          if (!(user instanceof CrmPrincipal principal) || !principal.usable()) {
            throw new DisabledException("Account unavailable");
          }
        });
    return provider;
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      IdentityService identities,
      TenantAccessService tenants,
      MfaService mfa,
      AccountNoticeService notices,
      Clock clock)
      throws Exception {
    return http.csrf(Customizer.withDefaults())
        .requestCache(cache -> cache.disable())
        .sessionManagement(
            session ->
                session
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    .sessionFixation(fixation -> fixation.changeSessionId()))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/actuator/health",
                        "/v1/auth/csrf",
                        LOGIN_PATH,
                        INVITATION_PREVIEW_PATH,
                        INVITATION_ACCEPT_PATH)
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, error) ->
                            ApiErrors.write(
                                response,
                                401,
                                ApiErrors.AUTHENTICATION_REQUIRED,
                                "Autenticação necessária."))
                    .accessDeniedHandler(
                        (request, response, error) -> {
                          // A missing or stale CSRF token is recoverable: fetch a new one.
                          if (error instanceof CsrfException) {
                            ApiErrors.write(
                                response,
                                403,
                                ApiErrors.CSRF_INVALID,
                                "Token CSRF ausente ou expirado.");
                          } else {
                            ApiErrors.write(
                                response, 403, ApiErrors.ACCESS_DENIED, "Acesso negado.");
                          }
                        }))
        .formLogin(
            login ->
                login
                    .loginProcessingUrl(LOGIN_PATH)
                    .usernameParameter("email")
                    .passwordParameter("password")
                    .successHandler(
                        (request, response, authentication) -> {
                          // A new login, even of the same account on the same cookie, starts
                          // without clinic, without second-factor proof and with a fresh clock.
                          HttpSession session = request.getSession();
                          session.removeAttribute(SessionValidationFilter.SELECTED_TENANT);
                          session.removeAttribute(MfaGateFilter.MFA_PROOF);
                          session.removeAttribute(MfaGateFilter.MFA_FAILURES);
                          session.removeAttribute(MfaGateFilter.MFA_SETUP_NONCE);
                          session.setAttribute(
                              MfaGateFilter.PASSWORD_AUTHENTICATED_AT, clock.instant());
                          // Notices left by the platform (reset-mfa), which has no mail.
                          if (authentication.getPrincipal() instanceof CrmPrincipal principal) {
                            notices.deliverPending(principal);
                          }
                          response.setStatus(204);
                        })
                    .failureHandler(
                        // Same answer for unknown, wrong password, inactive or unverified account.
                        (request, response, error) ->
                            ApiErrors.write(
                                response,
                                401,
                                ApiErrors.INVALID_CREDENTIALS,
                                "E-mail ou senha inválidos.")))
        .logout(
            logout ->
                logout
                    .logoutUrl(LOGOUT_PATH)
                    .invalidateHttpSession(true)
                    .clearAuthentication(true)
                    .deleteCookies(SESSION_COOKIE)
                    .logoutSuccessHandler(
                        (request, response, authentication) -> response.setStatus(204)))
        .addFilterBefore(
            // Login, the anonymous invitation endpoints, which hash passwords too, and the
            // second-factor code, on top of its per-session and per-account limits.
            new LoginRateLimitFilter(
                Set.of(
                    LOGIN_PATH,
                    INVITATION_PREVIEW_PATH,
                    INVITATION_ACCEPT_PATH,
                    MfaGateFilter.VERIFY_PATH),
                clock),
            UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(
            new SessionValidationFilter(identities, tenants), AuthorizationFilter.class)
        // Same position, registered after it: the stable sort keeps the gate right after the
        // session validation, whatever the numeric order of the two.
        .addFilterBefore(new MfaGateFilter(mfa, clock), AuthorizationFilter.class)
        .build();
  }
}
