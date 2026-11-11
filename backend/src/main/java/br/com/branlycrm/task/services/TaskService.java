package br.com.branlycrm.task.services;

import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.common.domain.PageResult;
import br.com.branlycrm.common.dto.response.PageView;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.task.domain.Task;
import br.com.branlycrm.task.domain.TaskFields;
import br.com.branlycrm.task.domain.TaskSearch;
import br.com.branlycrm.task.domain.TaskText;
import br.com.branlycrm.task.dto.request.TaskRequest;
import br.com.branlycrm.task.dto.request.TaskStatusRequest;
import br.com.branlycrm.task.dto.response.TaskView;
import br.com.branlycrm.task.exceptions.TaskException;
import br.com.branlycrm.task.repositories.TaskRepository;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.domain.ClinicPermission;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.services.TenantAccessService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Tasks and follow-ups of the selected clinic (CRM-040). TASK_WRITE creates, edits, completes and
 * reopens (owner, manager, attendant); TASK_READ lists (every role). The responsible is an active
 * member who works tasks. Inputs are validated before the access check.
 */
@Service
public class TaskService {
  public static final int NOTE_MAX = 500;
  private static final Instant OLDEST_DUE = Instant.parse("2000-01-01T00:00:00Z");
  private static final Instant NEWEST_DUE = Instant.parse("2100-01-01T00:00:00Z");

  private final TenantAccessService access;
  private final TaskRepository tasks;

  public TaskService(TenantAccessService access, TaskRepository tasks) {
    this.access = access;
    this.tasks = tasks;
  }

  public TaskView create(UUID tenantId, UUID actor, TaskRequest request) {
    if (request == null) throw new InvalidInputException("body");
    TaskFields fields = fields(request);
    UUID contact = uuid(request.contactId(), "contactId", true);
    UUID lead = uuid(request.leadId(), "leadId", false);
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.TASK_WRITE);
    requireResponsible(tenantId, fields.responsibleId());
    return view(tasks.create(clinic, fields, contact, lead));
  }

  public TaskView edit(UUID tenantId, UUID actor, UUID taskId, TaskRequest request) {
    if (request == null) throw new InvalidInputException("body");
    TaskFields fields = fields(request);
    long version = version(request.version());
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.TASK_WRITE);
    // The responsible is checked only when it changes, so a task of someone who left the team
    // can still be edited; a missing or completed task answers first.
    Task current = tasks.get(clinic, taskId);
    if (current.status() == Task.TaskStatus.DONE) {
      throw new TaskException(TaskException.Reason.DONE);
    }
    if (!fields.responsibleId().equals(current.fields().responsibleId())) {
      requireResponsible(tenantId, fields.responsibleId());
    }
    return view(tasks.edit(clinic, taskId, version, fields));
  }

  public TaskView complete(UUID tenantId, UUID actor, UUID taskId, TaskStatusRequest request) {
    if (request == null) throw new InvalidInputException("body");
    long version = version(request.version());
    String note = TaskText.multiline(request.note(), NOTE_MAX, "note");
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.TASK_WRITE);
    return view(tasks.complete(clinic, taskId, version, note));
  }

  public TaskView reopen(UUID tenantId, UUID actor, UUID taskId, TaskStatusRequest request) {
    if (request == null) throw new InvalidInputException("body");
    long version = version(request.version());
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.TASK_WRITE);
    return view(tasks.reopen(clinic, taskId, version));
  }

  public TaskView get(UUID tenantId, UUID actor, UUID taskId) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.TASK_READ);
    return view(tasks.get(clinic, taskId));
  }

  /** {@code responsibleId=mine} filters the caller's own tasks. */
  public PageView<TaskView> list(
      UUID tenantId,
      UUID actor,
      String due,
      String responsibleId,
      String contactId,
      String leadId,
      String status,
      String page,
      String pageSize) {
    UUID responsible =
        "mine".equals(responsibleId) ? actor : uuid(responsibleId, "responsibleId", false);
    if (due != null
        && !due.isEmpty()
        && status != null
        && !status.isEmpty()
        && !"OPEN".equals(status)) {
      // The due windows only match open tasks.
      throw new InvalidInputException("status");
    }
    var search =
        new TaskSearch(
            optional(TaskSearch.Due.class, due, "due"),
            responsible,
            uuid(contactId, "contactId", false),
            uuid(leadId, "leadId", false),
            optional(Task.TaskStatus.class, status, "status"));
    PageQuery paging = PageQuery.of(page, pageSize);
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.TASK_READ);
    PageResult<Task> result = tasks.search(clinic, search, paging);
    return new PageView<>(
        result.items().stream().map(TaskService::view).toList(),
        paging.page(),
        paging.pageSize(),
        result.total());
  }

  private void requireResponsible(UUID tenantId, UUID responsible) {
    ClinicAccess member;
    try {
      member = access.authorize(tenantId, responsible);
    } catch (TenantAccessException notAMember) {
      throw new TaskException(TaskException.Reason.RESPONSIBLE_INVALID);
    }
    if (!member.allows(ClinicPermission.TASK_WRITE)) {
      throw new TaskException(TaskException.Reason.RESPONSIBLE_INVALID);
    }
  }

  private static TaskFields fields(TaskRequest request) {
    return new TaskFields(
        request.title(),
        request.description(),
        instant(request.dueAt()),
        uuid(request.responsibleId(), "responsibleId", true));
  }

  private static TaskView view(Task task) {
    TaskFields fields = task.fields();
    return new TaskView(
        task.id(),
        fields.title(),
        fields.description(),
        fields.dueAt(),
        fields.responsibleId(),
        task.contactId(),
        task.leadId(),
        task.status().name(),
        task.completedAt(),
        task.completedBy(),
        task.completionNote(),
        task.createdAt(),
        task.createdBy(),
        task.version());
  }

  private static Instant instant(String value) {
    if (value == null || value.isEmpty()) throw new InvalidInputException("dueAt");
    Instant due;
    try {
      due = OffsetDateTime.parse(value).toInstant();
    } catch (DateTimeParseException invalid) {
      throw new InvalidInputException("dueAt");
    }
    if (due.isBefore(OLDEST_DUE) || !due.isBefore(NEWEST_DUE)) {
      throw new InvalidInputException("dueAt");
    }
    return due;
  }

  private static long version(Long version) {
    if (version == null || version < 0) throw new InvalidInputException("version");
    return version;
  }

  private static UUID uuid(String value, String field, boolean required) {
    if (value == null || value.isEmpty()) {
      if (required) throw new InvalidInputException(field);
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException invalid) {
      throw new InvalidInputException(field);
    }
  }

  private static <E extends Enum<E>> E optional(Class<E> type, String value, String field) {
    if (value == null || value.isEmpty()) return null;
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException invalid) {
      throw new InvalidInputException(field);
    }
  }
}
