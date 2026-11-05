package br.com.branlycrm.identity.controllers;

import br.com.branlycrm.identity.exceptions.ClinicNotSelectedException;
import br.com.branlycrm.identity.exceptions.InvalidRequestException;
import br.com.branlycrm.tenant.exceptions.TeamException;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** HTTP translation for team endpoints; no SQL detail or stack trace reaches the client. */
@RestControllerAdvice(assignableTypes = TeamController.class)
public final class TeamErrorHandler {
  @ExceptionHandler(TeamException.class)
  ResponseEntity<Map<String, String>> team(TeamException exception) {
    int status =
        switch (exception.reason()) {
          case NOT_FOUND -> 404;
          case FORBIDDEN -> 403;
          case LAST_OWNER -> 409;
        };
    return ResponseEntity.status(status).body(Map.of("message", exception.getMessage()));
  }

  @ExceptionHandler(TenantAccessException.class)
  ResponseEntity<Map<String, String>> denied(TenantAccessException exception) {
    return ResponseEntity.status(403).body(Map.of("message", exception.getMessage()));
  }

  @ExceptionHandler(ClinicNotSelectedException.class)
  ResponseEntity<Map<String, String>> notSelected(ClinicNotSelectedException exception) {
    return ResponseEntity.status(409).body(Map.of("message", exception.getMessage()));
  }

  @ExceptionHandler({
    InvalidRequestException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<Map<String, String>> invalid() {
    return ResponseEntity.badRequest()
        .body(
            Map.of(
                "message",
                "Dados inválidos. Use um identificador válido e um papel entre OWNER, MANAGER,"
                    + " RECEPTION, SALES e PROFESSIONAL."));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<Map<String, String>> unavailable() {
    return ResponseEntity.status(503)
        .header("Retry-After", "5")
        .body(Map.of("message", "Serviço temporariamente indisponível."));
  }
}
