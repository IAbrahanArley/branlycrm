package br.com.branlycrm.common.controllers;

import br.com.branlycrm.common.dto.response.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the standard error body for controller advices and for filters that answer before Spring
 * MVC. Codes are stable contract; see docs/api-convencoes.md for the catalog.
 */
public final class ApiErrors {
  public static final String AUTHENTICATION_REQUIRED = "AUTHENTICATION_REQUIRED";
  public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
  public static final String SESSION_REVOKED = "SESSION_REVOKED";
  public static final String CSRF_INVALID = "CSRF_INVALID";
  public static final String ACCESS_DENIED = "ACCESS_DENIED";
  public static final String RATE_LIMITED = "RATE_LIMITED";
  public static final String CLINIC_ACCESS_DENIED = "CLINIC_ACCESS_DENIED";
  public static final String CLINIC_NOT_SELECTED = "CLINIC_NOT_SELECTED";
  public static final String INVALID_INPUT = "INVALID_INPUT";
  public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private ApiErrors() {}

  public static ResponseEntity<ApiError> response(int status, String code, String message) {
    return ResponseEntity.status(status).body(new ApiError(code, message));
  }

  public static ResponseEntity<ApiError> invalidInput(List<String> fields) {
    return ResponseEntity.badRequest()
        .body(new ApiError(INVALID_INPUT, "Dados inválidos.", fields));
  }

  public static ResponseEntity<ApiError> unavailable() {
    return ResponseEntity.status(503)
        .header("Retry-After", "5")
        .body(new ApiError(SERVICE_UNAVAILABLE, "Serviço temporariamente indisponível."));
  }

  /** For filters and security handlers, which write the response themselves. */
  public static void write(HttpServletResponse response, int status, String code, String message)
      throws IOException {
    if (response.isCommitted()) return;
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write(JSON.writeValueAsString(new ApiError(code, message)));
  }
}
