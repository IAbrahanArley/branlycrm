package br.com.branlycrm.whatsapp.controllers;

import br.com.branlycrm.common.dto.response.ApiError;
import br.com.branlycrm.common.web.ApiErrors;
import br.com.branlycrm.whatsapp.exceptions.ChannelException;
import org.springframework.http.ResponseEntity;

/** Shared translation of channel refusals for the clinic and agency handlers. */
final class ChannelErrors {
  private ChannelErrors() {}

  static ResponseEntity<ApiError> of(ChannelException exception) {
    String code = "CHANNEL_" + exception.reason().name();
    return switch (exception.reason()) {
      case ALREADY_CONNECTED ->
          ApiErrors.response(
              409, code, "A clínica já tem um número conectado. Desconecte-o antes.");
      case NOT_CONNECTED -> ApiErrors.response(409, code, "A clínica não tem número conectado.");
      case BUSY ->
          ApiErrors.response(409, code, "Uma conexão ou desconexão ainda está em andamento.");
      case CODE_INVALID ->
          ApiErrors.response(400, code, "A Meta recusou a autorização. Abra a conexão novamente.");
      case META_UNAVAILABLE, NOT_CONFIGURED ->
          ResponseEntity.status(503)
              .header("Retry-After", "30")
              .body(new ApiError(code, "Conexão com o WhatsApp indisponível no momento."));
    };
  }
}
