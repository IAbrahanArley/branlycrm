package br.com.branlycrm.whatsapp.controllers;

import br.com.branlycrm.common.dto.response.ApiError;
import br.com.branlycrm.common.web.ApiErrors;
import br.com.branlycrm.common.web.GlobalErrorHandler;
import br.com.branlycrm.whatsapp.exceptions.WebhookException;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * HTTP translation for the WhatsApp webhook. Every failure to store answers 5xx, never a partial
 * 200, so Meta retries (ADR-036).
 */
@RestControllerAdvice(assignableTypes = WhatsappWebhookController.class)
@Order(GlobalErrorHandler.MODULE_ORDER)
public final class WhatsappWebhookErrorHandler {
  @ExceptionHandler(WebhookException.class)
  ResponseEntity<ApiError> webhook(WebhookException exception) {
    String code = "WHATSAPP_WEBHOOK_" + exception.reason().name();
    return switch (exception.reason()) {
      case NOT_CONFIGURED, BUSY, STORAGE_FAILED ->
          ResponseEntity.status(503)
              .header("Retry-After", "5")
              .body(new ApiError(code, "Serviço temporariamente indisponível."));
      case VERIFICATION_FAILED -> ApiErrors.response(403, code, "Verificação recusada.");
      case SIGNATURE_INVALID -> ApiErrors.response(401, code, "Assinatura inválida.");
      case MALFORMED -> ApiErrors.response(400, code, "Conteúdo inválido.");
      case TOO_LARGE -> ApiErrors.response(413, code, "Conteúdo grande demais.");
    };
  }

  /** The connection broke while reading the body; Meta retries. */
  @ExceptionHandler(IOException.class)
  ResponseEntity<ApiError> unreadable(IOException exception) {
    return ApiErrors.response(400, "WHATSAPP_WEBHOOK_MALFORMED", "Conteúdo inválido.");
  }
}
