package br.com.branlycrm.whatsapp.controllers;

import br.com.branlycrm.config.SecurityConfiguration;
import br.com.branlycrm.whatsapp.exceptions.WebhookException;
import br.com.branlycrm.whatsapp.services.WhatsappWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public WhatsApp Cloud API webhook (CRM-082a, ADR-036). It has its own security chain: no session,
 * no CSRF, and the signature is the only authentication. The body is read raw and bounded, and
 * never logged.
 */
@Tag(name = "WhatsApp webhook", description = "Deliveries from the WhatsApp Cloud API (Meta)")
@RestController
public final class WhatsappWebhookController {
  /** Meta's documented maximum payload (ADR-036); Nginx enforces the same limit. */
  public static final int MAX_BODY = 3 * 1024 * 1024;

  private final WhatsappWebhookService webhook;

  public WhatsappWebhookController(WhatsappWebhookService webhook) {
    this.webhook = webhook;
  }

  @Operation(
      operationId = "verifyWhatsappWebhook",
      summary = "Verification handshake: echoes hub.challenge when hub.verify_token matches")
  @ApiResponse(responseCode = "200", description = "The challenge, as text")
  @ApiResponse(responseCode = "403", description = "WHATSAPP_WEBHOOK_VERIFICATION_FAILED")
  @GetMapping(SecurityConfiguration.WHATSAPP_WEBHOOK_PATH)
  public ResponseEntity<String> verify(
      @Parameter(name = "hub.mode") @RequestParam(name = "hub.mode", required = false) String mode,
      @Parameter(name = "hub.verify_token")
          @RequestParam(name = "hub.verify_token", required = false)
          String token,
      @Parameter(name = "hub.challenge") @RequestParam(name = "hub.challenge", required = false)
          String challenge) {
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_PLAIN)
        .body(webhook.verify(mode, token, challenge));
  }

  @Operation(
      operationId = "receiveWhatsappWebhook",
      summary =
          "Signed delivery (X-Hub-Signature-256): stored in the clinic before answering 200;"
              + " any storage failure answers 5xx so Meta retries")
  @ApiResponse(responseCode = "200", description = "Stored, duplicated or discarded")
  @ApiResponse(responseCode = "400", description = "WHATSAPP_WEBHOOK_MALFORMED")
  @ApiResponse(responseCode = "401", description = "WHATSAPP_WEBHOOK_SIGNATURE_INVALID")
  @ApiResponse(responseCode = "413", description = "WHATSAPP_WEBHOOK_TOO_LARGE")
  @PostMapping(SecurityConfiguration.WHATSAPP_WEBHOOK_PATH)
  public ResponseEntity<String> receive(
      @Parameter(hidden = true) HttpServletRequest request,
      @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature)
      throws IOException {
    webhook.receive(body(request), signature);
    return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body("EVENT_RECEIVED");
  }

  private static byte[] body(HttpServletRequest request) throws IOException {
    if (request.getContentLengthLong() > MAX_BODY) {
      throw new WebhookException(WebhookException.Reason.TOO_LARGE);
    }
    try (InputStream input = request.getInputStream()) {
      byte[] body = input.readNBytes(MAX_BODY + 1);
      if (body.length > MAX_BODY) throw new WebhookException(WebhookException.Reason.TOO_LARGE);
      return body;
    }
  }
}
