package br.com.branlycrm.catalog.controllers;

import br.com.branlycrm.catalog.exceptions.CatalogException;
import br.com.branlycrm.common.dto.response.ApiError;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.common.web.ApiErrors;
import br.com.branlycrm.common.web.GlobalErrorHandler;
import br.com.branlycrm.identity.exceptions.ClinicNotSelectedException;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * HTTP translation for the catalog endpoints. Clinic database failures are left to {@code
 * ClinicDataErrorHandler}.
 */
@RestControllerAdvice(assignableTypes = {ProcedureController.class, ProfessionalController.class})
@Order(GlobalErrorHandler.MODULE_ORDER)
public final class CatalogErrorHandler {
  @ExceptionHandler(CatalogException.class)
  ResponseEntity<ApiError> catalog(CatalogException exception) {
    String code = exception.reason().name();
    return switch (exception.reason()) {
      case PROCEDURE_NOT_FOUND -> ApiErrors.response(404, code, "Procedimento não encontrado.");
      case PROFESSIONAL_NOT_FOUND -> ApiErrors.response(404, code, "Profissional não encontrado.");
      case PROCEDURE_DUPLICATE ->
          ApiErrors.response(409, code, "Já existe um procedimento com este nome.");
      case PROFESSIONAL_DUPLICATE ->
          ApiErrors.response(409, code, "Já existe um profissional com este nome.");
      case PROCEDURE_STALE ->
          ApiErrors.response(
              409, code, "O procedimento foi alterado por outra pessoa. Recarregue e repita.");
      case PROFESSIONAL_STALE ->
          ApiErrors.response(
              409, code, "O profissional foi alterado por outra pessoa. Recarregue e repita.");
      case PROCEDURE_LIMIT ->
          ApiErrors.response(
              409,
              code,
              "A clínica atingiu o limite de procedimentos. Reaproveite um inativo editando-o.");
      case PROFESSIONAL_LIMIT ->
          ApiErrors.response(409, code, "A clínica atingiu o limite de profissionais.");
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
