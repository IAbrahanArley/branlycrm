package br.com.branlycrm.inbox.controllers;

import br.com.branlycrm.common.dto.response.ApiError;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.common.web.ApiErrors;
import br.com.branlycrm.common.web.GlobalErrorHandler;
import br.com.branlycrm.identity.exceptions.ClinicNotSelectedException;
import br.com.branlycrm.inbox.exceptions.InboxException;
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
 * HTTP translation for the templates (CRM-085). Clinic database failures are left to {@code
 * ClinicDataErrorHandler}.
 */
@RestControllerAdvice(assignableTypes = TemplateController.class)
@Order(GlobalErrorHandler.MODULE_ORDER)
public final class TemplateErrorHandler {
  @ExceptionHandler(InboxException.class)
  ResponseEntity<ApiError> inbox(InboxException exception) {
    String code = "CONVERSATION_" + exception.reason().name();
    return switch (exception.reason()) {
      case NOT_FOUND -> ApiErrors.response(404, code, "Conversa não encontrada.");
      case TAKEN ->
          ApiErrors.response(409, code, "Outra pessoa já assumiu esta conversa. Recarregue.");
      case STALE ->
          ApiErrors.response(
              409, code, "A conversa foi alterada por outra pessoa. Recarregue e repita.");
      case NOT_ASSIGNED -> ApiErrors.response(409, code, "Ninguém está atendendo esta conversa.");
      case NOT_YOURS ->
          ApiErrors.response(409, code, "Só o gestor solta a conversa de outra pessoa.");
      case WINDOW_CLOSED ->
          ApiErrors.response(
              409,
              code,
              "Passaram 24 horas desde a última mensagem do paciente. Só modelos aprovados podem"
                  + " ser enviados.");
      case MEDIA_NOT_FOUND -> ApiErrors.response(404, code, "Esta mensagem não tem arquivo.");
      case MEDIA_NOT_READY ->
          ApiErrors.response(409, code, "O arquivo ainda está sendo baixado. Tente em instantes.");
      case MEDIA_REJECTED ->
          ApiErrors.response(
              409, code, "O arquivo não foi aceito (tipo ou tamanho) ou não pôde ser baixado.");
      case MEDIA_UNAVAILABLE ->
          ResponseEntity.status(503)
              .header("Retry-After", "30")
              .body(new ApiError(code, "Arquivos indisponíveis no momento."));
      case MEDIA_TYPE_NOT_ALLOWED ->
          ApiErrors.response(
              415,
              code,
              "Tipo de arquivo não aceito. Envie JPG, PNG, MP4, 3GP, áudio (MP3, M4A, OGG, AAC,"
                  + " AMR), PDF, DOCX, XLSX ou PPTX.");
      case MEDIA_TOO_LARGE ->
          ApiErrors.response(
              413, code, "Arquivo grande demais: imagens até 5 MB, outros arquivos até 16 MB.");
      case TEMPLATE_NOT_FOUND -> ApiErrors.response(404, code, "Modelo não encontrado.");
      case TEMPLATE_NOT_APPROVED ->
          ApiErrors.response(
              409,
              code,
              "Este modelo não está aprovado pela Meta ou não é suportado. Sincronize os modelos"
                  + " e escolha outro.");
      case TEMPLATE_OPTED_OUT ->
          ApiErrors.response(
              409, code, "O contato pediu para não receber mensagens de marketing pelo WhatsApp.");
      case TEMPLATE_LIMIT ->
          ApiErrors.response(
              429, code, "A clínica atingiu o limite diário de envio de modelos. Tente amanhã.");
      case CHANNEL_NOT_CONNECTED ->
          ApiErrors.response(409, code, "A clínica não tem WhatsApp conectado.");
      case IDEMPOTENCY_CONFLICT ->
          ApiErrors.response(409, code, "Esta chave de envio já foi usada em outra conversa.");
      case ASSIGNEE_INVALID ->
          ApiErrors.response(409, code, "A pessoa escolhida não atende conversas nesta clínica.");
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
