package br.com.branlycrm.config;

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
                    .authenticationEntryPoint((request, response, error) -> response.setStatus(401))
                    .accessDeniedHandler((request, response, error) -> response.setStatus(403)))
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
                    .failureHandler((request, response, error) -> response.setStatus(401)))
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
