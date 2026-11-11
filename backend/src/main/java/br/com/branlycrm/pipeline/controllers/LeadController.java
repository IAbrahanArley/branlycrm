package br.com.branlycrm.pipeline.controllers;

import br.com.branlycrm.common.dto.response.PageView;
import br.com.branlycrm.identity.security.CrmPrincipal;
import br.com.branlycrm.identity.security.SelectedClinic;
import br.com.branlycrm.pipeline.dto.request.LossRequest;
import br.com.branlycrm.pipeline.dto.request.NoteRequest;
import br.com.branlycrm.pipeline.dto.request.OpenLeadRequest;
import br.com.branlycrm.pipeline.dto.request.OriginRequest;
import br.com.branlycrm.pipeline.dto.request.ResponsibleChangeRequest;
import br.com.branlycrm.pipeline.dto.request.StageChangeRequest;
import br.com.branlycrm.pipeline.dto.request.TemperatureChangeRequest;
import br.com.branlycrm.pipeline.dto.response.LeadConflictError;
import br.com.branlycrm.pipeline.dto.response.LeadEventView;
import br.com.branlycrm.pipeline.dto.response.LeadNoteView;
import br.com.branlycrm.pipeline.dto.response.LeadSummaryView;
import br.com.branlycrm.pipeline.dto.response.LeadView;
import br.com.branlycrm.pipeline.services.LeadRequestService;
import br.com.branlycrm.pipeline.services.LeadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Leads of the clinic selected in the session (CRM-032). */
@Tag(name = "Leads", description = "Leads, their transitions, timeline and notes")
@SecurityRequirement(name = "session")
@RestController
@RequestMapping("/v1/clinic/leads")
public final class LeadController {
  private final LeadRequestService requests;
  private final LeadService leads;

  public LeadController(LeadRequestService requests, LeadService leads) {
    this.requests = requests;
    this.leads = leads;
  }

  @Operation(operationId = "listLeads", summary = "List leads, open by default, newest first")
  @ApiResponse(responseCode = "200", description = "Page of leads")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT")
  @GetMapping
  public PageView<LeadSummaryView> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String stageId,
      @RequestParam(required = false) String responsibleId,
      @RequestParam(required = false) String temperature,
      @RequestParam(required = false) String contactId,
      @RequestParam(required = false) String page,
      @RequestParam(required = false) String pageSize,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return requests.list(
        SelectedClinic.require(session),
        principal.subjectId(),
        status,
        stageId,
        responsibleId,
        temperature,
        contactId,
        page,
        pageSize);
  }

  @Operation(operationId = "openLead", summary = "Open a lead for a contact in the entry stage")
  @ApiResponse(responseCode = "201", description = "Lead opened")
  @ApiResponse(
      responseCode = "409",
      description =
          "LEAD_ALREADY_OPEN (leadId), LEAD_CONTACT_UNAVAILABLE, LEAD_RESPONSIBLE_INVALID",
      content = @Content(schema = @Schema(implementation = LeadConflictError.class)))
  @SecurityRequirement(name = "csrf")
  @PostMapping
  public ResponseEntity<LeadView> open(
      @RequestBody OpenLeadRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    LeadView opened =
        requests.open(SelectedClinic.require(session), principal.subjectId(), request);
    return ResponseEntity.created(
            ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{leadId}")
                .buildAndExpand(opened.id())
                .toUri())
        .body(opened);
  }

  @Operation(operationId = "getLead", summary = "Lead detail")
  @ApiResponse(responseCode = "200", description = "Lead")
  @ApiResponse(responseCode = "404", description = "LEAD_NOT_FOUND")
  @GetMapping("/{leadId}")
  public LeadView get(
      @PathVariable UUID leadId,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return leads.get(SelectedClinic.require(session), principal.subjectId(), leadId);
  }

  @Operation(operationId = "moveLeadStage", summary = "Move an open lead to another open stage")
  @ApiResponse(responseCode = "200", description = "Lead moved")
  @ApiResponse(
      responseCode = "409",
      description = "LEAD_STALE, LEAD_CLOSED, LEAD_STAGE_NOT_ALLOWED")
  @SecurityRequirement(name = "csrf")
  @PutMapping("/{leadId}/stage")
  public LeadView moveStage(
      @PathVariable UUID leadId,
      @RequestBody StageChangeRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return requests.moveStage(
        SelectedClinic.require(session), principal.subjectId(), leadId, request);
  }

  @Operation(operationId = "changeLeadTemperature", summary = "Change the lead's temperature")
  @ApiResponse(responseCode = "200", description = "Lead updated")
  @ApiResponse(responseCode = "409", description = "LEAD_STALE, LEAD_CLOSED")
  @SecurityRequirement(name = "csrf")
  @PutMapping("/{leadId}/temperature")
  public LeadView changeTemperature(
      @PathVariable UUID leadId,
      @RequestBody TemperatureChangeRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return requests.changeTemperature(
        SelectedClinic.require(session), principal.subjectId(), leadId, request);
  }

  @Operation(operationId = "assignLead", summary = "Set or clear the lead's responsible")
  @ApiResponse(responseCode = "200", description = "Lead updated")
  @ApiResponse(
      responseCode = "409",
      description = "LEAD_STALE, LEAD_CLOSED, LEAD_RESPONSIBLE_INVALID")
  @SecurityRequirement(name = "csrf")
  @PutMapping("/{leadId}/responsible")
  public LeadView assign(
      @PathVariable UUID leadId,
      @RequestBody ResponsibleChangeRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return requests.assign(SelectedClinic.require(session), principal.subjectId(), leadId, request);
  }

  @Operation(operationId = "loseLead", summary = "Close the lead as lost with a reason")
  @ApiResponse(responseCode = "200", description = "Lead lost")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: lossReasonId, version")
  @ApiResponse(
      responseCode = "409",
      description = "LEAD_STALE, LEAD_CLOSED, LEAD_LOSS_REASON_NOT_FOUND")
  @SecurityRequirement(name = "csrf")
  @PostMapping("/{leadId}/loss")
  public LeadView lose(
      @PathVariable UUID leadId,
      @RequestBody LossRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return requests.lose(SelectedClinic.require(session), principal.subjectId(), leadId, request);
  }

  @Operation(
      operationId = "correctLeadOrigin",
      summary = "Correct the origin of an open or closed lead by hand (OWNER, MANAGER, ATTENDANT)")
  @ApiResponse(responseCode = "200", description = "Lead")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: type, code, title, version")
  @ApiResponse(responseCode = "409", description = "LEAD_STALE")
  @SecurityRequirement(name = "csrf")
  @PutMapping("/{leadId}/origin")
  public LeadView correctOrigin(
      @PathVariable UUID leadId,
      @RequestBody OriginRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return requests.correctOrigin(
        SelectedClinic.require(session), principal.subjectId(), leadId, request);
  }

  @Operation(operationId = "listLeadEvents", summary = "The lead's timeline, oldest first")
  @ApiResponse(responseCode = "200", description = "Events")
  @GetMapping("/{leadId}/events")
  public List<LeadEventView> events(
      @PathVariable UUID leadId,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return leads.history(SelectedClinic.require(session), principal.subjectId(), leadId);
  }

  @Operation(operationId = "listLeadNotes", summary = "The lead's notes, oldest first")
  @ApiResponse(responseCode = "200", description = "Notes")
  @GetMapping("/{leadId}/notes")
  public List<LeadNoteView> notes(
      @PathVariable UUID leadId,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return requests.notes(SelectedClinic.require(session), principal.subjectId(), leadId);
  }

  @Operation(operationId = "addLeadNote", summary = "Add a note to the lead")
  @ApiResponse(responseCode = "201", description = "Note added")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: body")
  @SecurityRequirement(name = "csrf")
  @PostMapping("/{leadId}/notes")
  public ResponseEntity<LeadNoteView> addNote(
      @PathVariable UUID leadId,
      @RequestBody NoteRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    LeadNoteView note =
        requests.addNote(SelectedClinic.require(session), principal.subjectId(), leadId, request);
    // Notes are not addressable one by one, so the 201 carries no Location.
    return ResponseEntity.status(201).body(note);
  }
}
