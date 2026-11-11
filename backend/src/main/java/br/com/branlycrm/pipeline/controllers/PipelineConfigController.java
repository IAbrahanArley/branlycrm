package br.com.branlycrm.pipeline.controllers;

import br.com.branlycrm.identity.security.CrmPrincipal;
import br.com.branlycrm.identity.security.SelectedClinic;
import br.com.branlycrm.pipeline.dto.request.ConfigArchiveRequest;
import br.com.branlycrm.pipeline.dto.request.ConfigNameRequest;
import br.com.branlycrm.pipeline.dto.request.StageOrderRequest;
import br.com.branlycrm.pipeline.dto.response.LossReasonView;
import br.com.branlycrm.pipeline.dto.response.PipelineView;
import br.com.branlycrm.pipeline.services.PipelineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configuration of the funnel stages and loss reasons (CRM-036), for owner and manager. Stage
 * writes send the funnel's {@code version} and answer with the whole funnel.
 */
@Tag(name = "Pipeline", description = "Sales funnel stages and loss reasons of the selected clinic")
@SecurityRequirement(name = "session")
@SecurityRequirement(name = "csrf")
@RestController
@RequestMapping("/v1/clinic")
public final class PipelineConfigController {
  private final PipelineService pipelines;

  public PipelineConfigController(PipelineService pipelines) {
    this.pipelines = pipelines;
  }

  @Operation(operationId = "createStage", summary = "Add an open stage at the end")
  @ApiResponse(responseCode = "201", description = "The funnel")
  @ApiResponse(
      responseCode = "409",
      description = "PIPELINE_STALE, STAGE_LIMIT (12 open), STAGE_DUPLICATE")
  @PostMapping("/pipeline/stages")
  public ResponseEntity<PipelineView> createStage(
      @RequestBody ConfigNameRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return ResponseEntity.status(201)
        .body(
            pipelines.createStage(SelectedClinic.require(session), principal.subjectId(), request));
  }

  @Operation(operationId = "renameStage", summary = "Rename an active stage, won and lost included")
  @ApiResponse(responseCode = "200", description = "The funnel")
  @ApiResponse(responseCode = "409", description = "PIPELINE_STALE, STAGE_DUPLICATE")
  @ApiResponse(responseCode = "404", description = "STAGE_NOT_FOUND")
  @PutMapping("/pipeline/stages/{stageId}")
  public PipelineView renameStage(
      @PathVariable UUID stageId,
      @RequestBody ConfigNameRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return pipelines.renameStage(
        SelectedClinic.require(session), principal.subjectId(), stageId, request);
  }

  @Operation(
      operationId = "reorderStages",
      summary = "Reorder the open stages: every active open stage id, in the new order")
  @ApiResponse(responseCode = "200", description = "The funnel")
  @ApiResponse(responseCode = "409", description = "PIPELINE_STALE, STAGE_ORDER_MISMATCH")
  @PutMapping("/pipeline/stages/order")
  public PipelineView reorder(
      @RequestBody StageOrderRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return pipelines.reorder(SelectedClinic.require(session), principal.subjectId(), request);
  }

  @Operation(
      operationId = "setStageArchived",
      summary = "Archive an open stage without open leads, or unarchive it at the end")
  @ApiResponse(responseCode = "200", description = "The funnel")
  @ApiResponse(responseCode = "404", description = "STAGE_NOT_FOUND")
  @ApiResponse(
      responseCode = "409",
      description =
          "PIPELINE_STALE, STAGE_PROTECTED, STAGE_HAS_OPEN_LEADS, STAGE_LIMIT,"
              + " STAGE_DUPLICATE")
  @PutMapping("/pipeline/stages/{stageId}/archived")
  public PipelineView setStageArchived(
      @PathVariable UUID stageId,
      @RequestBody ConfigArchiveRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return pipelines.setStageArchived(
        SelectedClinic.require(session), principal.subjectId(), stageId, request);
  }

  @Operation(operationId = "createLossReason", summary = "Add a loss reason")
  @ApiResponse(responseCode = "201", description = "Loss reason")
  @ApiResponse(responseCode = "409", description = "LOSS_REASON_LIMIT (50), LOSS_REASON_DUPLICATE")
  @PostMapping("/loss-reasons")
  public ResponseEntity<LossReasonView> createLossReason(
      @RequestBody ConfigNameRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return ResponseEntity.status(201)
        .body(
            pipelines.createLossReason(
                SelectedClinic.require(session), principal.subjectId(), request));
  }

  @Operation(operationId = "renameLossReason", summary = "Rename a loss reason")
  @ApiResponse(responseCode = "200", description = "Loss reason")
  @ApiResponse(responseCode = "409", description = "LOSS_REASON_STALE, LOSS_REASON_DUPLICATE")
  @ApiResponse(responseCode = "404", description = "LOSS_REASON_NOT_FOUND")
  @PutMapping("/loss-reasons/{reasonId}")
  public LossReasonView renameLossReason(
      @PathVariable UUID reasonId,
      @RequestBody ConfigNameRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return pipelines.renameLossReason(
        SelectedClinic.require(session), principal.subjectId(), reasonId, request);
  }

  @Operation(
      operationId = "setLossReasonArchived",
      summary = "Archive or unarchive a loss reason; one always stays active")
  @ApiResponse(responseCode = "200", description = "Loss reason")
  @ApiResponse(responseCode = "409", description = "LOSS_REASON_LAST_ACTIVE")
  @ApiResponse(responseCode = "404", description = "LOSS_REASON_NOT_FOUND")
  @PutMapping("/loss-reasons/{reasonId}/archived")
  public LossReasonView setLossReasonArchived(
      @PathVariable UUID reasonId,
      @RequestBody ConfigArchiveRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return pipelines.setLossReasonArchived(
        SelectedClinic.require(session), principal.subjectId(), reasonId, request);
  }
}
