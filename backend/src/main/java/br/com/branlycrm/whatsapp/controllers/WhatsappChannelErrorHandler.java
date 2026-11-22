package br.com.branlycrm.whatsapp.controllers;

import br.com.branlycrm.common.dto.response.ApiError;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.common.web.ApiErrors;
import br.com.branlycrm.common.web.GlobalErrorHandler;
import br.com.branlycrm.identity.exceptions.ClinicNotSelectedException;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.whatsapp.exceptions.ChannelException;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** HTTP translation for the clinic's channel endpoints. */
@RestControllerAdvice(assignableTypes = WhatsappChannelController.class)
@Order(GlobalErrorHandler.MODULE_ORDER)
public final class WhatsappChannelErrorHandler {
  @ExceptionHandler(ChannelException.class)
  ResponseEntity<ApiError> channel(ChannelException exception) {
    return ChannelErrors.of(exception);
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

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiError> unreadable() {
    return ApiErrors.invalidInput(List.of("body"));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<ApiError> unavailable(DataAccessException exception) {
    return ApiErrors.dataAccess(exception);
  }
}
