package br.com.branlycrm.identity.controllers;

import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** HTTP translation for session endpoints; no SQL detail or stack trace reaches the client. */
@RestControllerAdvice(assignableTypes = SessionController.class)
public final class SessionErrorHandler {
  @ExceptionHandler(TenantAccessException.class)
  ResponseEntity<Map<String, String>> denied(TenantAccessException exception) {
    return ResponseEntity.status(403).body(Map.of("message", exception.getMessage()));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<Map<String, String>> invalid() {
    return ResponseEntity.badRequest().body(Map.of("message", "Identificador inválido."));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<Map<String, String>> unavailable() {
    return ResponseEntity.status(503)
        .header("Retry-After", "5")
        .body(Map.of("message", "Serviço temporariamente indisponível."));
  }
}
