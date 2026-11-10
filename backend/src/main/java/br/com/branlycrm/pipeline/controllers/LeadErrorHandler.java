package br.com.branlycrm.pipeline.controllers;

import br.com.branlycrm.common.dto.response.ApiError;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.common.web.ApiErrors;
import br.com.branlycrm.common.web.GlobalErrorHandler;
import br.com.branlycrm.identity.exceptions.ClinicNotSelectedException;
import br.com.branlycrm.pipeline.dto.response.LeadConflictError;
import br.com.branlycrm.pipeline.exceptions.LeadException;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** HTTP translation for lead endpoints; clinic database failures go to ClinicDataErrorHandler. */
@RestControllerAdvice(assignableTypes = LeadController.class)
@Order(GlobalErrorHandler.MODULE_ORDER)
public final class LeadErrorHandler {
  @ExceptionHandler(LeadException.class)
  ResponseEntity<?> lead(LeadException exception) {
    return switch (exception.reason()) {
      case NOT_FOUND -> ApiErrors.response(404, "LEAD_NOT_FOUND", "Lead não encontrado.");
      case ALREADY_OPEN ->
          ResponseEntity.status(409)
              .body(
                  new LeadConflictError(
                      "LEAD_ALREADY_OPEN",
                      "Este contato já tem um lead aberto.",
                      List.of(),
                      exception.existingLeadId()));
      case STALE ->
          ApiErrors.response(
              409, "LEAD_STALE", "O lead foi alterado por outra pessoa. Recarregue e repita.");
      case CLOSED ->
          ApiErrors.response(409, "LEAD_CLOSED", "O lead já foi encerrado. Abra um novo lead.");
      case CONTACT_UNAVAILABLE ->
          ApiErrors.response(
              409, "LEAD_CONTACT_UNAVAILABLE", "O contato não existe ou está arquivado.");
      case STAGE_NOT_ALLOWED ->
          ApiErrors.response(
              409,
              "LEAD_STAGE_NOT_ALLOWED",
              "Etapa inválida. Venda fechada exige registrar a venda; perdido exige um motivo.");
      case LOSS_REASON_NOT_FOUND ->
          ApiErrors.response(409, "LEAD_LOSS_REASON_NOT_FOUND", "Motivo de perda inválido.");
      case RESPONSIBLE_INVALID ->
          ApiErrors.response(
              409, "LEAD_RESPONSIBLE_INVALID", "O responsável precisa ser da equipe ativa.");
    };
  }

  @ExceptionHandler(TenantAccessException.class)
  ResponseEntity<ApiError> denied(TenantAccessException exception) {
    return ApiErrors.response(403, ApiErrors.CLINIC_ACCESS_DENIED, exception.getMessage());
  }

  @ExceptionHandler(ClinicNotSelectedException.class)
  ResponseEntity<ApiError> notSelected(ClinicNotSelectedException exception) {
    return ApiErrors.response(409, ApiErrors.CLINIC_NOT_SELECTED, exception.getMessage());
  }

  @ExceptionHandler(InvalidInputException.class)
  ResponseEntity<ApiError> invalid(InvalidInputException exception) {
    return ApiErrors.invalidInput(exception.fields());
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ApiError> invalidParameter(MethodArgumentTypeMismatchException exception) {
    return ApiErrors.invalidInput(List.of(exception.getName()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiError> unreadable() {
    return ApiErrors.invalidInput(List.of("body"));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<ApiError> unavailable(DataAccessException exception) {
    return ApiErrors.dataAccess(exception);
  }
}
