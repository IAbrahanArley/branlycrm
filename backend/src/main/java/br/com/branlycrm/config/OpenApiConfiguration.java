package br.com.branlycrm.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documents the session cookie and CSRF header. Login and logout are Spring Security filters, so
 * they are described here instead of through a placeholder controller (ADR-015).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {
  @Bean
  OpenAPI branlyCrmApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Branly CRM API")
                .version("v1")
                .description(
                    "Sessão por cookie e CSRF em mutações. Dados de clínica exigem participação"
                        + " ativa; o tenant nunca é aceito apenas por parâmetro do cliente."))
        .components(
            new Components()
                .addSecuritySchemes(
                    "session",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE)
                        .name(SecurityConfiguration.SESSION_COOKIE))
                .addSecuritySchemes(
                    "csrf",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-CSRF-TOKEN")))
        .path(SecurityConfiguration.LOGIN_PATH, new PathItem().post(login()))
        .path(SecurityConfiguration.LOGOUT_PATH, new PathItem().post(logout()));
  }

  private static Operation login() {
    var form =
        new ObjectSchema()
            .addProperty("email", new StringSchema())
            .addProperty("password", new StringSchema().format("password"));
    form.addRequiredItem("email");
    form.addRequiredItem("password");
    return new Operation()
        .addTagsItem("Session")
        .summary("Authenticate with e-mail and password; renews the session id")
        .addSecurityItem(new SecurityRequirement().addList("csrf"))
        .requestBody(
            new RequestBody()
                .required(true)
                .content(
                    new Content()
                        .addMediaType(
                            "application/x-www-form-urlencoded", new MediaType().schema(form))))
        .responses(
            new ApiResponses()
                .addApiResponse("204", new ApiResponse().description("Authenticated"))
                .addApiResponse("401", new ApiResponse().description("Invalid credentials"))
                .addApiResponse("403", new ApiResponse().description("Missing or invalid CSRF"))
                .addApiResponse("429", new ApiResponse().description("Too many attempts")));
  }

  private static Operation logout() {
    return new Operation()
        .addTagsItem("Session")
        .summary("Invalidate the session and remove its cookie")
        .addSecurityItem(new SecurityRequirement().addList("session").addList("csrf"))
        .responses(
            new ApiResponses()
                .addApiResponse("204", new ApiResponse().description("Session ended"))
                .addApiResponse("403", new ApiResponse().description("Missing or invalid CSRF")));
  }
}
