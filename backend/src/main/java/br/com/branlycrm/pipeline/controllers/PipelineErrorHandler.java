package br.com.branlycrm.pipeline.controllers;

import br.com.branlycrm.common.dto.response.ApiError;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.common.web.ApiErrors;
import br.com.branlycrm.common.web.GlobalErrorHandler;
import br.com.branlycrm.identity.exceptions.ClinicNotSelectedException;
import br.com.branlycrm.pipeline.exceptions.PipelineException;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * HTTP translation for funnel endpoints; clinic database failures are left to {@code
 * ClinicDataErrorHandler}.
 */
@RestControllerAdvice(assignableTypes = {PipelineController.class, PipelineConfigController.class})
@Order(GlobalErrorHandler.MODULE_ORDER)
public final class PipelineErrorHandler {
  @ExceptionHandler(PipelineException.class)
  ResponseEntity<ApiError> pipeline(PipelineException exception) {
    String code = exception.reason().name();
    String message =
        switch (exception.reason()) {
          case PIPELINE_STALE -> "O funil foi alterado por outra pessoa. Recarregue e repita.";
          case STAGE_NOT_FOUND -> "Etapa não encontrada.";
          case STAGE_DUPLICATE -> "Já existe uma etapa ativa com este nome.";
          case STAGE_LIMIT -> "O funil já tem o máximo de 12 etapas abertas.";
          case STAGE_PROTECTED -> "Venda fechada, Perdido e Novo não podem ser arquivadas.";
          case STAGE_HAS_OPEN_LEADS -> "Há leads abertos nesta etapa. Mova-os antes de arquivar.";
          case STAGE_ORDER_MISMATCH ->
              "A ordem precisa listar exatamente as etapas abertas atuais. Recarregue.";
          case LOSS_REASON_NOT_FOUND -> "Motivo de perda não encontrado.";
          case LOSS_REASON_DUPLICATE -> "Já existe um motivo com este nome, talvez arquivado.";
          case LOSS_REASON_STALE -> "O motivo foi alterado por outra pessoa. Recarregue e repita.";
          case LOSS_REASON_LIMIT -> "A clínica já tem o máximo de 50 motivos de perda.";
          case LOSS_REASON_LAST_ACTIVE -> "Mantenha ao menos um motivo de perda ativo.";
        };
    return ApiErrors.response(code.endsWith("NOT_FOUND") ? 404 : 409, code, message);
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

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<ApiError> unavailable(DataAccessException exception) {
    return ApiErrors.dataAccess(exception);
  }
}
