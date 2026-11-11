package br.com.branlycrm.task.controllers;

import br.com.branlycrm.common.dto.response.ApiError;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.common.web.ApiErrors;
import br.com.branlycrm.common.web.GlobalErrorHandler;
import br.com.branlycrm.identity.exceptions.ClinicNotSelectedException;
import br.com.branlycrm.task.exceptions.TaskException;
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
 * HTTP translation for the task endpoints. Clinic database failures are left to {@code
 * ClinicDataErrorHandler}.
 */
@RestControllerAdvice(assignableTypes = TaskController.class)
@Order(GlobalErrorHandler.MODULE_ORDER)
public final class TaskErrorHandler {
  @ExceptionHandler(TaskException.class)
  ResponseEntity<ApiError> task(TaskException exception) {
    String code = "TASK_" + exception.reason().name();
    return switch (exception.reason()) {
      case NOT_FOUND -> ApiErrors.response(404, code, "Tarefa não encontrada.");
      case STALE ->
          ApiErrors.response(
              409, code, "A tarefa foi alterada por outra pessoa. Recarregue e repita.");
      case DONE -> ApiErrors.response(409, code, "A tarefa está concluída. Reabra para alterá-la.");
      case CONTACT_UNAVAILABLE ->
          ApiErrors.response(409, code, "O contato não existe ou está arquivado.");
      case LEAD_MISMATCH ->
          ApiErrors.response(409, code, "O lead não existe ou é de outro contato.");
      case RESPONSIBLE_INVALID ->
          ApiErrors.response(409, code, "O responsável precisa ser da equipe ativa.");
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
