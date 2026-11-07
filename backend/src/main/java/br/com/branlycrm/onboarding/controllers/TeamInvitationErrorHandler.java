package br.com.branlycrm.onboarding.controllers;

import br.com.branlycrm.common.dto.response.ApiError;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.common.web.ApiErrors;
import br.com.branlycrm.common.web.GlobalErrorHandler;
import br.com.branlycrm.identity.exceptions.ClinicNotSelectedException;
import br.com.branlycrm.onboarding.exceptions.InvitationException;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** HTTP translation for team invitations; an invitation of another clinic is simply not found. */
@RestControllerAdvice(assignableTypes = TeamInvitationController.class)
@Order(GlobalErrorHandler.MODULE_ORDER)
public final class TeamInvitationErrorHandler {
  public static final String INVITATION_NOT_FOUND = "INVITATION_NOT_FOUND";
  public static final String INVITATION_FORBIDDEN = "INVITATION_FORBIDDEN";
  public static final String INVITATION_SEND_LIMIT = "INVITATION_SEND_LIMIT";

  @ExceptionHandler(InvitationException.class)
  ResponseEntity<ApiError> invitation(InvitationException exception) {
    return switch (exception.reason()) {
      case FORBIDDEN -> ApiErrors.response(403, INVITATION_FORBIDDEN, exception.getMessage());
      case SEND_LIMIT -> ApiErrors.response(429, INVITATION_SEND_LIMIT, exception.getMessage());
      case MEMBER_ALREADY_ACTIVE ->
          ApiErrors.response(
              409, InvitationErrorHandler.MEMBER_ALREADY_ACTIVE, exception.getMessage());
      default -> ApiErrors.response(404, INVITATION_NOT_FOUND, "Convite não encontrado.");
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
