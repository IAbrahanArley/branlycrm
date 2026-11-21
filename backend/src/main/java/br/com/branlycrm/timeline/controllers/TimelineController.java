package br.com.branlycrm.timeline.controllers;

import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.common.dto.response.PageView;
import br.com.branlycrm.identity.security.CrmPrincipal;
import br.com.branlycrm.identity.security.SelectedClinic;
import br.com.branlycrm.timeline.dto.response.TimelineEntryView;
import br.com.branlycrm.timeline.services.TimelineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Unified timeline of a contact of the selected clinic (CRM-023). */
@Tag(name = "Timeline", description = "Unified timeline of a contact")
@SecurityRequirement(name = "session")
@RestController
@RequestMapping("/v1/clinic/contacts")
public final class TimelineController {
  private final TimelineService timeline;

  public TimelineController(TimelineService timeline) {
    this.timeline = timeline;
  }

  @Operation(
      operationId = "getContactTimeline",
      summary =
          "Lead events, notes, sale events and tasks of the contact, newest first (all roles)")
  @ApiResponse(responseCode = "200", description = "Timeline page")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: contactId, page or pageSize")
  @ApiResponse(responseCode = "404", description = "CONTACT_NOT_FOUND")
  @GetMapping("/{contactId}/timeline")
  public PageView<TimelineEntryView> contact(
      @PathVariable UUID contactId,
      @RequestParam(required = false) String page,
      @RequestParam(required = false) String pageSize,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return timeline.contact(
        SelectedClinic.require(session),
        principal.subjectId(),
        contactId,
        PageQuery.of(page, pageSize));
  }
}
