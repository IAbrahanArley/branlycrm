package br.com.branlycrm.identity.controllers;

import br.com.branlycrm.common.dto.response.ApiError;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.common.web.ApiErrors;
import br.com.branlycrm.common.web.GlobalErrorHandler;
import br.com.branlycrm.identity.exceptions.ClinicNotSelectedException;
import br.com.branlycrm.tenant.exceptions.TeamException;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** HTTP translation for team endpoints; no SQL detail or stack trace reaches the client. */
@RestControllerAdvice(assignableTypes = TeamController.class)
@Order(GlobalErrorHandler.MODULE_ORDER)
public final class TeamErrorHandler {
  public static final String MEMBER_NOT_FOUND = "MEMBER_NOT_FOUND";
  public static final String MEMBER_CHANGE_FORBIDDEN = "MEMBER_CHANGE_FORBIDDEN";
  public static final String LAST_OWNER = "LAST_OWNER";

  @ExceptionHandler(TeamException.class)
  ResponseEntity<ApiError> team(TeamException exception) {
    return switch (exception.reason()) {
      case NOT_FOUND -> ApiErrors.response(404, MEMBER_NOT_FOUND, exception.getMessage());
      case FORBIDDEN -> ApiErrors.response(403, MEMBER_CHANGE_FORBIDDEN, exception.getMessage());
      case LAST_OWNER -> ApiErrors.response(409, LAST_OWNER, exception.getMessage());
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
  ResponseEntity<ApiError> unavailable() {
    return ApiErrors.unavailable();
  }
}
