package br.com.branlycrm.common.web;

import br.com.branlycrm.common.dto.response.ApiError;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Fallback for every request handled by Spring MVC, so no error escapes the standard body
 * (docs/api-convencoes.md). Module advices run first ({@link #MODULE_ORDER}); this one only sees
 * what they do not handle: unknown routes, wrong methods or media types, and internal errors.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public final class GlobalErrorHandler {
  /** Order for module advices, which must win over this fallback. */
  public static final int MODULE_ORDER = 0;

  private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ApiError> notFound() {
    return ApiErrors.response(404, ApiErrors.NOT_FOUND, "Recurso não encontrado.");
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ApiError> methodNotAllowed() {
    return ApiErrors.response(405, ApiErrors.METHOD_NOT_ALLOWED, "Método não permitido.");
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ApiError> unsupportedMediaType() {
    return ApiErrors.response(
        415, ApiErrors.UNSUPPORTED_MEDIA_TYPE, "Tipo de conteúdo não suportado.");
  }

  @ExceptionHandler(InvalidInputException.class)
  ResponseEntity<ApiError> invalid(InvalidInputException exception) {
    return ApiErrors.invalidInput(exception.fields());
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  ResponseEntity<ApiError> missingParameter(MissingServletRequestParameterException exception) {
    return ApiErrors.invalidInput(List.of(exception.getParameterName()));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ApiError> mismatch(MethodArgumentTypeMismatchException exception) {
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

  /**
   * Spring Security exceptions thrown inside a handler must reach ExceptionTranslationFilter, which
   * answers 401/403 with the standard body; rethrowing hands them back.
   */
  @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
  void security(RuntimeException exception) {
    throw exception;
  }

  /** A bug: logged with its stack trace on the server, never shown to the client. */
  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> internal(Exception exception, HttpServletRequest request) {
    log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), exception);
    return ApiErrors.response(500, ApiErrors.INTERNAL_ERROR, "Erro interno. Tente novamente.");
  }
}
