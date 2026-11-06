package br.com.branlycrm.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documents the session cookie and CSRF header. Login and logout are Spring Security filters, so
 * they are described here instead of through a placeholder controller (ADR-015).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
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

  /**
   * Adds the errors every operation can return (docs/api-convencoes.md) and the ApiError body to
   * each error response, so controllers only declare their own outcomes.
   */
  @Bean
  OpenApiCustomizer standardErrorResponses() {
    return api -> {
      api.getComponents()
          .addSchemas(
              "ApiError",
              new ObjectSchema()
                  .addProperty("code", new StringSchema())
                  .addProperty("message", new StringSchema())
                  .addProperty("fields", new ArraySchema().items(new StringSchema())));
      api.getPaths()
          .forEach(
              (path, item) ->
                  item.readOperationsMap()
                      .forEach(
                          (method, operation) -> {
                            ApiResponses responses = operation.getResponses();
                            if (!PUBLIC_PATHS.contains(path)) {
                              merge(responses, "401", "AUTHENTICATION_REQUIRED or SESSION_REVOKED");
                            }
                            if (method != PathItem.HttpMethod.GET) {
                              merge(responses, "403", "CSRF_INVALID");
                            }
                            merge(responses, "500", "INTERNAL_ERROR");
                            merge(responses, "503", "SERVICE_UNAVAILABLE; honor Retry-After");
                            responses.forEach(
                                (status, response) -> {
                                  if (status.startsWith("4") || status.startsWith("5")) {
                                    response.setContent(
                                        new Content()
                                            .addMediaType(
                                                "application/json",
                                                new MediaType()
                                                    .schema(
                                                        new Schema<>()
                                                            .$ref(
                                                                "#/components/schemas/ApiError"))));
                                  }
                                });
                          }));
    };
  }

  private static final Set<String> PUBLIC_PATHS =
      Set.of("/v1/auth/csrf", SecurityConfiguration.LOGIN_PATH);

  private static void merge(ApiResponses responses, String status, String code) {
    ApiResponse existing = responses.get(status);
    if (existing == null) {
      responses.addApiResponse(status, new ApiResponse().description(code));
    } else if (existing.getDescription() == null || !existing.getDescription().contains(code)) {
      existing.setDescription(
          existing.getDescription() == null ? code : existing.getDescription() + "; " + code);
    }
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
                .addApiResponse("401", new ApiResponse().description("INVALID_CREDENTIALS"))
                .addApiResponse("403", new ApiResponse().description("CSRF_INVALID"))
                .addApiResponse(
                    "429", new ApiResponse().description("RATE_LIMITED; honor Retry-After")));
  }

  private static Operation logout() {
    return new Operation()
        .addTagsItem("Session")
        .summary("Invalidate the session and remove its cookie")
        .addSecurityItem(new SecurityRequirement().addList("session").addList("csrf"))
        .responses(
            new ApiResponses()
                .addApiResponse("204", new ApiResponse().description("Session ended"))
                .addApiResponse("403", new ApiResponse().description("CSRF_INVALID")));
  }
}
