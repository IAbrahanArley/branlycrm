package br.com.branlycrm.agency.controllers;

import br.com.branlycrm.agency.exceptions.AgencyAccessException;
import br.com.branlycrm.common.dto.response.ApiError;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.common.web.ApiErrors;
import br.com.branlycrm.common.web.GlobalErrorHandler;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** HTTP translation for agency endpoints; an agency of someone else is simply not found. */
@RestControllerAdvice(assignableTypes = AgencyController.class)
@Order(GlobalErrorHandler.MODULE_ORDER)
public final class AgencyErrorHandler {
  public static final String AGENCY_NOT_FOUND = "AGENCY_NOT_FOUND";

  @ExceptionHandler(AgencyAccessException.class)
  ResponseEntity<ApiError> notFound(AgencyAccessException exception) {
    return ApiErrors.response(404, AGENCY_NOT_FOUND, exception.getMessage());
  }

  @ExceptionHandler(InvalidInputException.class)
  ResponseEntity<ApiError> invalid(InvalidInputException exception) {
    return ApiErrors.invalidInput(exception.fields());
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ApiError> invalidParameter(MethodArgumentTypeMismatchException exception) {
    return ApiErrors.invalidInput(List.of(exception.getName()));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<ApiError> unavailable(DataAccessException exception) {
    return ApiErrors.dataAccess(exception);
  }
}
