package br.com.branlycrm.inbox.controllers;

import br.com.branlycrm.identity.security.CrmPrincipal;
import br.com.branlycrm.identity.security.SelectedClinic;
import br.com.branlycrm.inbox.dto.response.TemplateView;
import br.com.branlycrm.inbox.services.InboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Message templates copied from the clinic's WhatsApp account (CRM-085). */
@Tag(name = "Inbox", description = "WhatsApp conversations shared by the clinic's team")
@SecurityRequirement(name = "session")
@RestController
@RequestMapping("/v1/clinic/inbox/templates")
public final class TemplateController {
  private final InboxService inbox;

  public TemplateController(InboxService inbox) {
    this.inbox = inbox;
  }

  @Operation(
      operationId = "listTemplates",
      summary =
          "Templates of the clinic's WhatsApp account (every role); sendable ones are approved by"
              + " Meta and supported by the CRM")
  @ApiResponse(responseCode = "200", description = "Templates, sendable first")
  @GetMapping
  public List<TemplateView> list(
      @AuthenticationPrincipal CrmPrincipal principal, HttpSession session) {
    return inbox.templates(SelectedClinic.require(session), principal.subjectId());
  }

  @Operation(
      operationId = "syncTemplates",
      summary =
          "Ask for a new copy of the templates from Meta within a minute (OWNER, MANAGER); for a"
              + " template approved minutes ago")
  @ApiResponse(responseCode = "202", description = "Requested")
  @ApiResponse(responseCode = "403", description = "CLINIC_ACCESS_DENIED")
  @ApiResponse(responseCode = "409", description = "CONVERSATION_CHANNEL_NOT_CONNECTED")
  @SecurityRequirement(name = "csrf")
  @PostMapping("/sync")
  public ResponseEntity<Void> sync(
      @AuthenticationPrincipal CrmPrincipal principal, HttpSession session) {
    inbox.requestTemplateSync(SelectedClinic.require(session), principal.subjectId());
    return ResponseEntity.accepted().build();
  }
}
