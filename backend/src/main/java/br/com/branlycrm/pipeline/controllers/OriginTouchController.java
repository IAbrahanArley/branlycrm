package br.com.branlycrm.pipeline.controllers;

import br.com.branlycrm.identity.security.CrmPrincipal;
import br.com.branlycrm.identity.security.SelectedClinic;
import br.com.branlycrm.pipeline.dto.response.OriginTouchView;
import br.com.branlycrm.pipeline.services.LeadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Later arrivals of a contact with their origin (CRM-090), for the patient history. */
@Tag(name = "Leads", description = "Leads, their transitions, timeline and notes")
@SecurityRequirement(name = "session")
@RestController
@RequestMapping("/v1/clinic/contacts")
public final class OriginTouchController {
  private final LeadService leads;

  public OriginTouchController(LeadService leads) {
    this.leads = leads;
  }

  @Operation(
      operationId = "listOriginTouches",
      summary = "The contact's last 100 arrivals with a detected origin, newest first")
  @ApiResponse(responseCode = "200", description = "Origin touches")
  @GetMapping("/{contactId}/origin-touches")
  public List<OriginTouchView> touches(
      @PathVariable UUID contactId,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return leads.touches(SelectedClinic.require(session), principal.subjectId(), contactId);
  }
}
