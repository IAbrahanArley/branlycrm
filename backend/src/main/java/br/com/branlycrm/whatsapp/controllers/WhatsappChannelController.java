package br.com.branlycrm.whatsapp.controllers;

import br.com.branlycrm.identity.security.CrmPrincipal;
import br.com.branlycrm.identity.security.SelectedClinic;
import br.com.branlycrm.whatsapp.dto.request.ConnectChannelRequest;
import br.com.branlycrm.whatsapp.dto.response.ChannelView;
import br.com.branlycrm.whatsapp.services.WhatsappChannelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** WhatsApp number of the clinic selected in the session (CRM-081b). */
@Tag(name = "WhatsApp channel", description = "Connection of the clinic's WhatsApp number")
@SecurityRequirement(name = "session")
@RestController
@RequestMapping("/v1/clinic/whatsapp/channel")
public final class WhatsappChannelController {
  private final WhatsappChannelService channels;

  public WhatsappChannelController(WhatsappChannelService channels) {
    this.channels = channels;
  }

  @Operation(operationId = "getWhatsappChannel", summary = "Channel status (OWNER, MANAGER)")
  @ApiResponse(responseCode = "200", description = "Status; NONE when no number is connected")
  @GetMapping
  public ChannelView status(@AuthenticationPrincipal CrmPrincipal principal, HttpSession session) {
    return channels.status(SelectedClinic.require(session), principal.subjectId());
  }

  @Operation(
      operationId = "connectWhatsappChannel",
      summary =
          "Connects the number from the Embedded Signup code (OWNER, MANAGER); the worker then"
              + " confirms the number, registers it and subscribes the webhooks")
  @ApiResponse(responseCode = "202", description = "CONNECTING")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT or CHANNEL_CODE_INVALID")
  @ApiResponse(responseCode = "409", description = "CHANNEL_ALREADY_CONNECTED")
  @SecurityRequirement(name = "csrf")
  @PostMapping("/connect")
  public ResponseEntity<ChannelView> connect(
      @RequestBody ConnectChannelRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return ResponseEntity.accepted()
        .body(channels.connect(SelectedClinic.require(session), principal.subjectId(), request));
  }

  @Operation(
      operationId = "disconnectWhatsappChannel",
      summary = "Disconnects the number (OWNER, MANAGER); also the way out of FAILED")
  @ApiResponse(responseCode = "202", description = "DISCONNECTING")
  @ApiResponse(responseCode = "409", description = "CHANNEL_NOT_CONNECTED or CHANNEL_BUSY")
  @SecurityRequirement(name = "csrf")
  @PostMapping("/disconnect")
  public ResponseEntity<ChannelView> disconnect(
      @AuthenticationPrincipal CrmPrincipal principal, HttpSession session) {
    return ResponseEntity.accepted()
        .body(channels.disconnect(SelectedClinic.require(session), principal.subjectId()));
  }
}
