package br.com.branlycrm.onboarding.controllers;

import br.com.branlycrm.common.dto.response.ApiError;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.common.web.ApiErrors;
import br.com.branlycrm.common.web.GlobalErrorHandler;
import br.com.branlycrm.onboarding.exceptions.InvitationException;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** HTTP translation for the public invitation endpoints; one code for every unusable token. */
@RestControllerAdvice(assignableTypes = InvitationController.class)
@Order(GlobalErrorHandler.MODULE_ORDER)
public final class InvitationErrorHandler {
  public static final String INVITATION_INVALID = "INVITATION_INVALID";
  public static final String INVITATION_LOGIN_REQUIRED = "INVITATION_LOGIN_REQUIRED";
  public static final String INVITATION_ACCOUNT_MISMATCH = "INVITATION_ACCOUNT_MISMATCH";
  public static final String MEMBER_ALREADY_ACTIVE = "MEMBER_ALREADY_ACTIVE";

  @ExceptionHandler(InvitationException.class)
  ResponseEntity<ApiError> invitation(InvitationException exception) {
    return switch (exception.reason()) {
      case LOGIN_REQUIRED ->
          ApiErrors.response(409, INVITATION_LOGIN_REQUIRED, exception.getMessage());
      case ACCOUNT_MISMATCH ->
          ApiErrors.response(409, INVITATION_ACCOUNT_MISMATCH, exception.getMessage());
      case MEMBER_ALREADY_ACTIVE ->
          ApiErrors.response(409, MEMBER_ALREADY_ACTIVE, exception.getMessage());
      default -> ApiErrors.response(404, INVITATION_INVALID, "Convite inválido ou expirado.");
    };
  }

  @ExceptionHandler(InvalidInputException.class)
  ResponseEntity<ApiError> invalid(InvalidInputException exception) {
    return ApiErrors.invalidInput(exception.fields());
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
