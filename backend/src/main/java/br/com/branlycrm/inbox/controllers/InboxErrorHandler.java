package br.com.branlycrm.inbox.controllers;

import br.com.branlycrm.common.dto.response.ApiError;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.common.web.ApiErrors;
import br.com.branlycrm.common.web.GlobalErrorHandler;
import br.com.branlycrm.identity.exceptions.ClinicNotSelectedException;
import br.com.branlycrm.inbox.exceptions.InboxException;
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
 * HTTP translation for the inbox. Clinic database failures are left to {@code
 * ClinicDataErrorHandler}.
 */
@RestControllerAdvice(assignableTypes = InboxController.class)
@Order(GlobalErrorHandler.MODULE_ORDER)
public final class InboxErrorHandler {
  @ExceptionHandler(InboxException.class)
  ResponseEntity<ApiError> inbox(InboxException exception) {
    String code = "CONVERSATION_" + exception.reason().name();
    return switch (exception.reason()) {
      case NOT_FOUND -> ApiErrors.response(404, code, "Conversa não encontrada.");
      case TAKEN ->
          ApiErrors.response(409, code, "Outra pessoa já assumiu esta conversa. Recarregue.");
      case STALE ->
          ApiErrors.response(
              409, code, "A conversa foi alterada por outra pessoa. Recarregue e repita.");
      case NOT_ASSIGNED -> ApiErrors.response(409, code, "Ninguém está atendendo esta conversa.");
      case NOT_YOURS ->
          ApiErrors.response(409, code, "Só o gestor solta a conversa de outra pessoa.");
      case ASSIGNEE_INVALID ->
          ApiErrors.response(409, code, "A pessoa escolhida não atende conversas nesta clínica.");
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
