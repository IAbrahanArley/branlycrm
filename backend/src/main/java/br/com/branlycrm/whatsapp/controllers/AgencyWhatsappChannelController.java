package br.com.branlycrm.whatsapp.controllers;

import br.com.branlycrm.identity.security.CrmPrincipal;
import br.com.branlycrm.whatsapp.dto.request.ConnectChannelRequest;
import br.com.branlycrm.whatsapp.dto.response.ChannelView;
import br.com.branlycrm.whatsapp.services.WhatsappChannelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The agency connects the WhatsApp number of a clinic in its portfolio (CRM-081b, ADR-026). */
@Tag(name = "Agencies", description = "Partner agencies and their clinic portfolio")
@SecurityRequirement(name = "session")
@RestController
@RequestMapping("/v1/agencies/{agencyId}/clinics/{tenantId}/whatsapp/channel")
public final class AgencyWhatsappChannelController {
  private final WhatsappChannelService channels;

  public AgencyWhatsappChannelController(WhatsappChannelService channels) {
    this.channels = channels;
  }

  @Operation(operationId = "getAgencyClinicWhatsappChannel", summary = "Channel status")
  @ApiResponse(responseCode = "200", description = "Status; NONE when no number is connected")
  @ApiResponse(responseCode = "404", description = "AGENCY_NOT_FOUND or CLINIC_NOT_FOUND")
  @GetMapping
  public ChannelView status(
      @PathVariable UUID agencyId,
      @PathVariable UUID tenantId,
      @AuthenticationPrincipal CrmPrincipal principal) {
    return channels.agencyStatus(agencyId, tenantId, principal.subjectId());
  }

  @Operation(
      operationId = "connectAgencyClinicWhatsappChannel",
      summary = "Connects the clinic's number from the Embedded Signup code")
  @ApiResponse(responseCode = "202", description = "CONNECTING")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT or CHANNEL_CODE_INVALID")
  @ApiResponse(responseCode = "404", description = "AGENCY_NOT_FOUND or CLINIC_NOT_FOUND")
  @ApiResponse(responseCode = "409", description = "CHANNEL_ALREADY_CONNECTED")
  @SecurityRequirement(name = "csrf")
  @PostMapping("/connect")
  public ResponseEntity<ChannelView> connect(
      @PathVariable UUID agencyId,
      @PathVariable UUID tenantId,
      @RequestBody ConnectChannelRequest request,
      @AuthenticationPrincipal CrmPrincipal principal) {
    return ResponseEntity.accepted()
        .body(channels.agencyConnect(agencyId, tenantId, principal.subjectId(), request));
  }

  @Operation(
      operationId = "disconnectAgencyClinicWhatsappChannel",
      summary = "Disconnects the clinic's number")
  @ApiResponse(responseCode = "202", description = "DISCONNECTING")
  @ApiResponse(responseCode = "404", description = "AGENCY_NOT_FOUND or CLINIC_NOT_FOUND")
  @ApiResponse(responseCode = "409", description = "CHANNEL_NOT_CONNECTED or CHANNEL_BUSY")
  @SecurityRequirement(name = "csrf")
  @PostMapping("/disconnect")
  public ResponseEntity<ChannelView> disconnect(
      @PathVariable UUID agencyId,
      @PathVariable UUID tenantId,
      @AuthenticationPrincipal CrmPrincipal principal) {
    return ResponseEntity.accepted()
        .body(channels.agencyDisconnect(agencyId, tenantId, principal.subjectId()));
  }
}
