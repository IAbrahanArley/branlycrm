package br.com.branlycrm.identity.mfa.controllers;

import br.com.branlycrm.common.dto.response.ApiError;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.common.web.ApiErrors;
import br.com.branlycrm.common.web.GlobalErrorHandler;
import br.com.branlycrm.identity.mfa.exceptions.MfaException;
import br.com.branlycrm.identity.security.MfaGateFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.util.WebUtils;

/**
 * HTTP translation of the second factor. Wrong, reused and unknown codes share one answer; the
 * fifth wrong code of a session ends that session, on top of the per-account lock.
 */
@RestControllerAdvice(assignableTypes = MfaController.class)
@Order(GlobalErrorHandler.MODULE_ORDER)
public final class MfaErrorHandler {
  public static final String MFA_CODE_INVALID = "MFA_CODE_INVALID";
  public static final String MFA_LOCKED = "MFA_LOCKED";
  public static final String MFA_SETUP_EXPIRED = "MFA_SETUP_EXPIRED";
  public static final String MFA_MANDATORY = "MFA_MANDATORY";
  public static final String MFA_NOT_ENROLLED = "MFA_NOT_ENROLLED";

  static final int MAX_FAILURES_PER_SESSION = 5;

  @ExceptionHandler(MfaException.class)
  ResponseEntity<ApiError> mfa(MfaException exception, HttpServletRequest request) {
    return switch (exception.reason()) {
      case CODE_INVALID -> invalidCode(request);
      case LOCKED ->
          ResponseEntity.status(429)
              .header("Retry-After", Long.toString(Math.max(1, exception.retryAfterSeconds())))
              .body(
                  new ApiError(
                      MFA_LOCKED, "Muitos códigos incorretos. Aguarde para tentar de novo."));
      case SETUP_EXPIRED ->
          ApiErrors.response(
              409, MFA_SETUP_EXPIRED, "O cadastro expirou ou foi substituído. Comece de novo.");
      case MANDATORY ->
          ApiErrors.response(403, MFA_MANDATORY, "O segundo fator é obrigatório para esta conta.");
      case NOT_ENROLLED ->
          ApiErrors.response(409, MFA_NOT_ENROLLED, "Nenhum segundo fator cadastrado.");
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
  ResponseEntity<ApiError> unavailable(DataAccessException exception) {
    return ApiErrors.dataAccess(exception);
  }

  private static ResponseEntity<ApiError> invalidCode(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session != null) {
      boolean exhausted;
      // Parallel requests of one session must not each read the same count.
      synchronized (WebUtils.getSessionMutex(session)) {
        try {
          int failures =
              (session.getAttribute(MfaGateFilter.MFA_FAILURES) instanceof Integer count
                      ? count
                      : 0)
                  + 1;
          exhausted = failures >= MAX_FAILURES_PER_SESSION;
          if (exhausted) {
            session.invalidate();
          } else {
            session.setAttribute(MfaGateFilter.MFA_FAILURES, failures);
          }
        } catch (IllegalStateException alreadyInvalidated) {
          exhausted = true;
        }
      }
      if (exhausted) {
        SecurityContextHolder.clearContext();
        return ApiErrors.response(401, ApiErrors.SESSION_REVOKED, "Sessão encerrada.");
      }
    }
    return ApiErrors.response(400, MFA_CODE_INVALID, "Código inválido.");
  }
}
