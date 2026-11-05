package br.com.branlycrm.api.config;

import br.com.branlycrm.api.identity.IdentityService;
import br.com.branlycrm.api.identity.LoginRateLimitFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import br.com.branlycrm.api.identity.SessionValidationFilter;
import br.com.branlycrm.api.tenancy.TenantAccessService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http,
            IdentityService identities, TenantAccessService tenants) throws Exception {
        return http
            .csrf(Customizer.withDefaults())
            .requestCache(cache -> cache.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(fixation -> fixation.changeSessionId()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/v1/auth/csrf", "/v1/auth/login").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, error) -> response.setStatus(401))
                .accessDeniedHandler((request, response, error) -> response.setStatus(403)))
            .formLogin(login -> login.loginProcessingUrl("/v1/auth/login")
                .usernameParameter("email").passwordParameter("password")
                .successHandler((request, response, auth) -> {
                    request.getSession().removeAttribute(SessionValidationFilter.SELECTED_TENANT);
                    response.setStatus(204);
                })
                .failureHandler((request, response, error) -> response.setStatus(401)))
            .logout(logout -> logout.logoutUrl("/v1/auth/logout")
                .invalidateHttpSession(true).clearAuthentication(true)
                .deleteCookies("BRANLY_SESSION")
                .logoutSuccessHandler((request, response, auth) -> response.setStatus(204)))
            .addFilterBefore(new LoginRateLimitFilter(), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new SessionValidationFilter(identities, tenants), AuthorizationFilter.class)
            .build();
    }
}