package br.com.branlycrm.task.controllers;

import br.com.branlycrm.common.dto.response.PageView;
import br.com.branlycrm.identity.security.CrmPrincipal;
import br.com.branlycrm.identity.security.SelectedClinic;
import br.com.branlycrm.task.dto.request.TaskRequest;
import br.com.branlycrm.task.dto.request.TaskStatusRequest;
import br.com.branlycrm.task.dto.response.TaskView;
import br.com.branlycrm.task.services.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
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

/** Tasks and follow-ups of the clinic selected in the session (CRM-040). */
@Tag(name = "Tasks", description = "Tasks and follow-ups linked to contacts and leads")
@SecurityRequirement(name = "session")
@RestController
@RequestMapping("/v1/clinic/tasks")
public final class TaskController {
  private final TaskService tasks;

  public TaskController(TaskService tasks) {
    this.tasks = tasks;
  }

  @Operation(
      operationId = "listTasks",
      summary =
          "List tasks: open by due date unless status=DONE; due=OVERDUE|TODAY|UPCOMING in the"
              + " clinic's time zone; responsibleId=mine for one's own")
  @ApiResponse(responseCode = "200", description = "Page of tasks")
  @GetMapping
  public PageView<TaskView> list(
      @RequestParam(required = false) String due,
      @RequestParam(required = false) String responsibleId,
      @RequestParam(required = false) String contactId,
      @RequestParam(required = false) String leadId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String page,
      @RequestParam(required = false) String pageSize,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return tasks.list(
        SelectedClinic.require(session),
        principal.subjectId(),
        due,
        responsibleId,
        contactId,
        leadId,
        status,
        page,
        pageSize);
  }

  @Operation(
      operationId = "createTask",
      summary = "Create a task for a contact or one of its leads")
  @ApiResponse(responseCode = "201", description = "Task created")
  @ApiResponse(
      responseCode = "409",
      description = "TASK_CONTACT_UNAVAILABLE, TASK_LEAD_MISMATCH, TASK_RESPONSIBLE_INVALID")
  @SecurityRequirement(name = "csrf")
  @PostMapping
  public ResponseEntity<TaskView> create(
      @RequestBody TaskRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    TaskView created =
        tasks.create(SelectedClinic.require(session), principal.subjectId(), request);
    return ResponseEntity.created(
            ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{taskId}")
                .buildAndExpand(created.id())
                .toUri())
        .body(created);
  }

  @Operation(operationId = "getTask", summary = "Task detail")
  @ApiResponse(responseCode = "200", description = "Task")
  @ApiResponse(responseCode = "404", description = "TASK_NOT_FOUND")
  @GetMapping("/{taskId}")
  public TaskView get(
      @PathVariable UUID taskId,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return tasks.get(SelectedClinic.require(session), principal.subjectId(), taskId);
  }

  @Operation(operationId = "editTask", summary = "Edit an open task (contact and lead stay)")
  @ApiResponse(responseCode = "200", description = "Task")
  @ApiResponse(responseCode = "404", description = "TASK_NOT_FOUND")
  @ApiResponse(
      responseCode = "409",
      description = "TASK_STALE, TASK_DONE, TASK_RESPONSIBLE_INVALID")
  @SecurityRequirement(name = "csrf")
  @PutMapping("/{taskId}")
  public TaskView edit(
      @PathVariable UUID taskId,
      @RequestBody TaskRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return tasks.edit(SelectedClinic.require(session), principal.subjectId(), taskId, request);
  }

  @Operation(operationId = "completeTask", summary = "Complete an open task, with an optional note")
  @ApiResponse(responseCode = "200", description = "Task")
  @ApiResponse(responseCode = "404", description = "TASK_NOT_FOUND")
  @ApiResponse(responseCode = "409", description = "TASK_STALE, TASK_DONE")
  @SecurityRequirement(name = "csrf")
  @PostMapping("/{taskId}/completion")
  public TaskView complete(
      @PathVariable UUID taskId,
      @RequestBody TaskStatusRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return tasks.complete(SelectedClinic.require(session), principal.subjectId(), taskId, request);
  }

  @Operation(operationId = "reopenTask", summary = "Reopen a completed task")
  @ApiResponse(responseCode = "200", description = "Task")
  @ApiResponse(responseCode = "404", description = "TASK_NOT_FOUND")
  @ApiResponse(responseCode = "409", description = "TASK_STALE")
  @SecurityRequirement(name = "csrf")
  @PostMapping("/{taskId}/reopening")
  public TaskView reopen(
      @PathVariable UUID taskId,
      @RequestBody TaskStatusRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return tasks.reopen(SelectedClinic.require(session), principal.subjectId(), taskId, request);
  }
}
