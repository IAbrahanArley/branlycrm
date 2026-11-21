package br.com.branlycrm.whatsapp.controllers;

import br.com.branlycrm.agency.exceptions.AgencyAccessException;
import br.com.branlycrm.common.dto.response.ApiError;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.common.web.ApiErrors;
import br.com.branlycrm.common.web.GlobalErrorHandler;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.whatsapp.exceptions.ChannelException;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * HTTP translation for the agency's channel endpoints: a clinic outside the portfolio answers like
 * a missing one (ADR-026).
 */
@RestControllerAdvice(assignableTypes = AgencyWhatsappChannelController.class)
@Order(GlobalErrorHandler.MODULE_ORDER)
public final class AgencyWhatsappChannelErrorHandler {
  @ExceptionHandler(ChannelException.class)
  ResponseEntity<ApiError> channel(ChannelException exception) {
    return ChannelErrors.of(exception);
  }

  @ExceptionHandler(AgencyAccessException.class)
  ResponseEntity<ApiError> agency(AgencyAccessException exception) {
    return ApiErrors.response(404, "AGENCY_NOT_FOUND", exception.getMessage());
  }

  @ExceptionHandler(TenantAccessException.class)
  ResponseEntity<ApiError> clinic(TenantAccessException exception) {
    return ApiErrors.response(404, "CLINIC_NOT_FOUND", "Clínica não encontrada.");
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
