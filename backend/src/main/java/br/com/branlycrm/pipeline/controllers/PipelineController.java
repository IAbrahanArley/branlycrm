package br.com.branlycrm.pipeline.controllers;

import br.com.branlycrm.identity.security.CrmPrincipal;
import br.com.branlycrm.identity.security.SelectedClinic;
import br.com.branlycrm.pipeline.dto.response.LossReasonView;
import br.com.branlycrm.pipeline.dto.response.PipelineView;
import br.com.branlycrm.pipeline.services.PipelineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sales funnel and loss reasons of the clinic selected in the session (CRM-030). Read only; the
 * configuration API comes with CRM-036.
 */
@Tag(name = "Pipeline", description = "Sales funnel stages and loss reasons of the selected clinic")
@SecurityRequirement(name = "session")
@RestController
@RequestMapping("/v1/clinic")
public final class PipelineController {
  private final PipelineService pipelines;

  public PipelineController(PipelineService pipelines) {
    this.pipelines = pipelines;
  }

  @Operation(
      operationId = "getPipeline",
      summary = "The clinic's sales funnel with its active stages in display order (all roles)",
      description =
          "Open stages by position, then the won stage, then the lost one. Default stages carry a"
              + " stable key (NEW is where every lead starts) whatever their name.")
  @ApiResponse(responseCode = "200", description = "Funnel and stages")
  @ApiResponse(responseCode = "403", description = "CLINIC_ACCESS_DENIED")
  @ApiResponse(responseCode = "409", description = "CLINIC_NOT_SELECTED")
  @GetMapping("/pipeline")
  public PipelineView pipeline(
      @AuthenticationPrincipal CrmPrincipal principal, HttpSession session) {
    return pipelines.pipeline(SelectedClinic.require(session), principal.subjectId());
  }

  @Operation(
      operationId = "listLossReasons",
      summary = "Loss reasons of the clinic ordered by name (all roles)")
  @ApiResponse(responseCode = "200", description = "Loss reasons")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: includeArchived")
  @ApiResponse(responseCode = "403", description = "CLINIC_ACCESS_DENIED")
  @ApiResponse(responseCode = "409", description = "CLINIC_NOT_SELECTED")
  @GetMapping("/loss-reasons")
  public List<LossReasonView> lossReasons(
      @Parameter(description = "true includes archived reasons; default false")
          @RequestParam(required = false)
          String includeArchived,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return pipelines.lossReasons(
        SelectedClinic.require(session), principal.subjectId(), includeArchived);
  }
}
