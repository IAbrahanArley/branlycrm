package br.com.branlycrm.tenant.controllers;

import br.com.branlycrm.common.dto.response.ApiError;
import br.com.branlycrm.common.web.ApiErrors;
import br.com.branlycrm.common.web.GlobalErrorHandler;
import br.com.branlycrm.tenant.exceptions.ClinicDataException;
import br.com.branlycrm.tenant.secrets.TenantSecretException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Failures of a clinic database, for every module that reads one (ADR-030). It runs after the
 * module advices and before the global fallback, so these never become a 500 with a stack trace:
 * the persistence unit has already logged a stable code, SQLState and clinic.
 */
@RestControllerAdvice
@Order(GlobalErrorHandler.MODULE_ORDER + 100)
public final class ClinicDataErrorHandler {
  private static final Logger LOG = LoggerFactory.getLogger(ClinicDataErrorHandler.class);

  @ExceptionHandler(ClinicDataException.class)
  ResponseEntity<ApiError> clinicData(ClinicDataException exception) {
    return switch (exception.reason()) {
      // Outage, pending migration or a registry incident: retryable for the client.
      case UNAVAILABLE, SCHEMA_OUTDATED, IDENTITY_MISMATCH -> ApiErrors.unavailable();
      case CONFLICT, STALE, RULE_VIOLATION ->
          ApiErrors.response(
              409, ApiErrors.STATE_CONFLICT, "A operação conflita com o estado atual.");
      case INVALID_DATA ->
          ApiErrors.response(500, ApiErrors.INTERNAL_ERROR, "Erro interno. Tente novamente.");
    };
  }

  /**
   * A clinic credential that is missing, unreadable or invalid stops every request of that clinic:
   * logged with its reason only (never the value or the file path). INVALID covers a secret that
   * belongs to another clinic, so it is a security incident and answers 500, not a retryable 503.
   */
  @ExceptionHandler(TenantSecretException.class)
  ResponseEntity<ApiError> secret(TenantSecretException exception) {
    if (exception.reason() == TenantSecretException.Reason.INVALID) {
      LOG.error("SECURITY clinic credential invalid reason={}", exception.reason());
      return ApiErrors.response(500, ApiErrors.INTERNAL_ERROR, "Erro interno. Tente novamente.");
    }
    LOG.error("Clinic credential unavailable reason={}", exception.reason());
    return ApiErrors.unavailable();
  }
}
