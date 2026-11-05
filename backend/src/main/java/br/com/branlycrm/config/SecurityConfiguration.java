package br.com.branlycrm.config;

import br.com.branlycrm.common.controllers.ApiErrors;
import br.com.branlycrm.identity.security.LoginRateLimitFilter;
import br.com.branlycrm.identity.security.SessionValidationFilter;
import br.com.branlycrm.identity.services.IdentityService;
import br.com.branlycrm.tenant.services.TenantAccessService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * Spring Security filters and answer with plain status codes, never redirects.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class SecurityConfiguration {
  public static final String SESSION_COOKIE = "BRANLY_SESSION";
  public static final String LOGIN_PATH = "/v1/auth/login";
  public static final String LOGOUT_PATH = "/v1/auth/logout";

  @Bean
  PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, IdentityService identities, TenantAccessService tenants) throws Exception {
    return http.csrf(Customizer.withDefaults())
        .requestCache(cache -> cache.disable())
        .sessionManagement(
            session ->
                session
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    .sessionFixation(fixation -> fixation.changeSessionId()))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health", "/v1/auth/csrf", LOGIN_PATH)
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
                          request
                              .getSession()
                              .removeAttribute(SessionValidationFilter.SELECTED_TENANT);
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
            new LoginRateLimitFilter(LOGIN_PATH), UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(
            new SessionValidationFilter(identities, tenants), AuthorizationFilter.class)
        .build();
  }
}
