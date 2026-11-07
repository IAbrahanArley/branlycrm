package br.com.branlycrm.onboarding.controllers;

import br.com.branlycrm.agency.controllers.AgencyErrorHandler;
import br.com.branlycrm.agency.exceptions.AgencyAccessException;
import br.com.branlycrm.common.dto.response.ApiError;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.common.web.ApiErrors;
import br.com.branlycrm.common.web.GlobalErrorHandler;
import br.com.branlycrm.onboarding.exceptions.AgencyClinicException;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** HTTP translation for portfolio writes; foreign agencies and clinics are simply not found. */
@RestControllerAdvice(assignableTypes = AgencyClinicController.class)
@Order(GlobalErrorHandler.MODULE_ORDER)
public final class AgencyClinicErrorHandler {
  public static final String CLINIC_NOT_FOUND = "CLINIC_NOT_FOUND";
  public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";
  public static final String AGENCY_PENDING_CLINIC_LIMIT = "AGENCY_PENDING_CLINIC_LIMIT";
  public static final String AGENCY_CLINIC_HAS_OWNER = "AGENCY_CLINIC_HAS_OWNER";

  @ExceptionHandler(AgencyAccessException.class)
  ResponseEntity<ApiError> agency(AgencyAccessException exception) {
    return ApiErrors.response(404, AgencyErrorHandler.AGENCY_NOT_FOUND, exception.getMessage());
  }

  @ExceptionHandler(AgencyClinicException.class)
  ResponseEntity<ApiError> clinic(AgencyClinicException exception) {
    return switch (exception.reason()) {
      case CLINIC_NOT_FOUND -> ApiErrors.response(404, CLINIC_NOT_FOUND, exception.getMessage());
      case IDEMPOTENCY_KEY_REUSED ->
          ApiErrors.response(409, IDEMPOTENCY_KEY_REUSED, exception.getMessage());
      case PENDING_LIMIT ->
          ApiErrors.response(409, AGENCY_PENDING_CLINIC_LIMIT, exception.getMessage());
      case CLINIC_HAS_OWNER ->
          ApiErrors.response(409, AGENCY_CLINIC_HAS_OWNER, exception.getMessage());
      case SEND_LIMIT ->
          ApiErrors.response(
              429, TeamInvitationErrorHandler.INVITATION_SEND_LIMIT, exception.getMessage());
    };
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
