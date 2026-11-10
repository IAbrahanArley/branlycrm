package br.com.branlycrm.contact.controllers;

import br.com.branlycrm.common.dto.response.ApiError;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.common.web.ApiErrors;
import br.com.branlycrm.common.web.GlobalErrorHandler;
import br.com.branlycrm.contact.dto.response.ContactConflictError;
import br.com.branlycrm.contact.exceptions.ContactException;
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
 * HTTP translation for contact and tag endpoints. Messages are written here in Portuguese and never
 * carry names, phones or e-mails; clinic database failures are left to {@code
 * ClinicDataErrorHandler}.
 */
@RestControllerAdvice(assignableTypes = {ContactController.class, TagController.class})
@Order(GlobalErrorHandler.MODULE_ORDER)
public final class ContactErrorHandler {
  public static final String CONTACT_NOT_FOUND = "CONTACT_NOT_FOUND";
  public static final String CONTACT_DUPLICATE_PHONE = "CONTACT_DUPLICATE_PHONE";
  public static final String CONTACT_STALE = "CONTACT_STALE";
  public static final String CONTACT_ARCHIVED = "CONTACT_ARCHIVED";
  public static final String CONSENT_REFUSED = "CONSENT_REFUSED";
  public static final String TAG_NOT_FOUND = "TAG_NOT_FOUND";
  public static final String TAG_DUPLICATE = "TAG_DUPLICATE";
  public static final String TAG_LIMIT = "TAG_LIMIT";

  @ExceptionHandler(ContactException.class)
  ResponseEntity<?> contact(ContactException exception) {
    return switch (exception.reason()) {
      case NOT_FOUND -> ApiErrors.response(404, CONTACT_NOT_FOUND, "Contato não encontrado.");
      case DUPLICATE_PHONE ->
          ResponseEntity.status(409)
              .body(
                  new ContactConflictError(
                      CONTACT_DUPLICATE_PHONE,
                      "Já existe um contato com este telefone.",
                      List.of("phone"),
                      exception.existingContactId()));
      case STALE ->
          ApiErrors.response(
              409, CONTACT_STALE, "O contato foi alterado por outra pessoa. Recarregue e repita.");
      case ARCHIVED ->
          ApiErrors.response(
              409, CONTACT_ARCHIVED, "O contato está arquivado. Desarquive para alterá-lo.");
      case CONSENT_REFUSED ->
          ApiErrors.response(
              409, CONSENT_REFUSED, "A mudança de consentimento não é permitida por esta origem.");
      case TAG_NOT_FOUND -> ApiErrors.response(404, TAG_NOT_FOUND, "Tag não encontrada.");
      case TAG_LIMIT ->
          ApiErrors.response(409, TAG_LIMIT, "A clínica atingiu o limite de tags. Exclua alguma.");
      case DUPLICATE_TAG ->
          ApiErrors.response(409, TAG_DUPLICATE, "Já existe uma tag com este nome.");
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
