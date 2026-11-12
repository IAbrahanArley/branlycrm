import { ApiError, apiRequest } from "@/lib/api/client";
import { isContactId } from "@/lib/api/contacts";

export const taskTabs = { OVERDUE: "Atrasadas", TODAY: "Hoje", UPCOMING: "Próximas", DONE: "Concluídas" } as const;
export type TaskTab = keyof typeof taskTabs;
export type TaskScope = { contactId?: string; leadId?: string };
export type Task = {
  id: string;
  title: string;
  description: string | null;
  dueAt: string;
  responsibleId: string;
  contactId: string;
  leadId: string | null;
  status: "OPEN" | "DONE";
  version: number;
  completedAt: string | null;
  completionNote: string | null;
};
type TaskPage = { items: Task[]; total: number; page: number; pageSize: number };
type TaskFields = { title: string; description: string | null; dueAt: string; responsibleId: string };
type NewTask = TaskFields & { contactId: string; leadId: string | null };

const base = "/v1/clinic/tasks";
const headers = { "Content-Type": "application/json" };
export const taskPageSize = 20;
export const taskTitles = { create: "Nova tarefa", edit: "Editar tarefa", complete: "Concluir tarefa", reopen: "Reabrir tarefa" } as const;

export function taskPath(tab: TaskTab) {
  return `/app/tarefas?filtro=${tab}` as const;
}

function isTask(task: Task | null): task is Task {
  return Boolean(task && isContactId(task.id) && isContactId(task.contactId) && isContactId(task.responsibleId)
    && (task.leadId === null || isContactId(task.leadId)) && typeof task.title === "string"
    && (task.status === "OPEN" || task.status === "DONE") && Number.isSafeInteger(task.version)
    && Number.isFinite(Date.parse(task.dueAt)) && (task.completedAt === null || Number.isFinite(Date.parse(task.completedAt)))
    && (task.description === null || typeof task.description === "string")
    && (task.completionNote === null || typeof task.completionNote === "string"));
}

export async function listTasks({ tab, mine, page, contactId, leadId }: TaskScope & { tab: TaskTab; mine: boolean; page: number }, signal: AbortSignal) {
  const params = new URLSearchParams({ page: String(page), pageSize: String(taskPageSize) });
  if (tab === "DONE") params.set("status", "DONE");
  else params.set("due", tab);
  if (mine) params.set("responsibleId", "mine");
  if (contactId) params.set("contactId", contactId);
  if (leadId) params.set("leadId", leadId);
  const result = await apiRequest<TaskPage | null>(`${base}?${params}`, { signal });
  if (!result || result.page !== page || result.pageSize !== taskPageSize || !Number.isSafeInteger(result.total)
      || result.total < 0 || !Array.isArray(result.items) || !result.items.every((task) =>
        isTask(task) && task.status === (tab === "DONE" ? "DONE" : "OPEN")
        && (!contactId || task.contactId === contactId) && (!leadId || task.leadId === leadId))) {
    throw new ApiError(200, "UNEXPECTED_RESPONSE", "A API não retornou uma lista de tarefas válida.");
  }
  return result;
}

async function writeTask(path: `/v1/${string}`, method: "POST" | "PUT", body: object, confirms: (task: Task) => boolean) {
  const task = await apiRequest<Task | null>(path, { method, headers, body: JSON.stringify(body) });
  if (!isTask(task) || !confirms(task)) {
    throw new ApiError(200, "UNEXPECTED_RESPONSE", "A API não confirmou a alteração da tarefa.");
  }
}

function matchesFields(task: Task, fields: TaskFields) {
  return task.title === fields.title && task.responsibleId === fields.responsibleId
    && task.description === fields.description && Date.parse(task.dueAt) === Date.parse(fields.dueAt);
}

export function createTask(draft: NewTask) {
  return writeTask(base, "POST", draft, (task) =>
    task.status === "OPEN" && task.contactId === draft.contactId && task.leadId === draft.leadId && matchesFields(task, draft));
}

export function editTask(task: Task, fields: TaskFields) {
  return writeTask(`${base}/${task.id}`, "PUT", { ...fields, version: task.version },
    (result) => result.id === task.id && result.status === "OPEN" && result.version > task.version && matchesFields(result, fields));
}

export function changeTaskStatus(task: Task, action: "complete" | "reopen", note: string | null) {
  const path = action === "complete" ? "completion" : "reopening";
  const status = action === "complete" ? "DONE" : "OPEN";
  return writeTask(`${base}/${task.id}/${path}`, "POST", { version: task.version, ...(action === "complete" ? { note } : {}) },
    (result) => result.id === task.id && result.status === status && result.version > task.version
      && (action === "complete" ? result.completedAt !== null && result.completionNote === note : result.completedAt === null));
}

export function taskProblem(error: ApiError) {
  const messages: Record<string, string> = {
    TASK_NOT_FOUND: "A tarefa não está disponível nesta clínica.",
    TASK_STALE: "A tarefa mudou desde a última consulta. Atualize a lista antes de tentar novamente.",
    TASK_DONE: "Esta tarefa foi concluída. Atualize a lista e reabra a tarefa antes de editá-la.",
    TASK_CONTACT_UNAVAILABLE: "O contato foi arquivado ou está indisponível. Escolha outro contato.",
    TASK_LEAD_MISMATCH: "O lead está indisponível ou não pertence ao contato selecionado.",
    TASK_RESPONSIBLE_INVALID: "Escolha um responsável ativo com permissão para trabalhar com tarefas.",
    INVALID_INPUT: "Revise os dados informados e tente novamente.",
    CSRF_INVALID: "A confirmação de segurança expirou. Tente novamente.",
    CLINIC_ACCESS_DENIED: "Sua conta não tem permissão para acessar tarefas nesta clínica.",
    ACCESS_DENIED: "Sua conta não tem permissão para esta ação.",
    SERVICE_UNAVAILABLE: "As tarefas estão indisponíveis no momento. Tente novamente em instantes.",
    NETWORK_ERROR: "A conexão com as tarefas está indisponível. Tente novamente.",
  };
  return {
    message: Object.hasOwn(messages, error.code) ? messages[error.code] : "Não foi possível concluir a solicitação de tarefas.",
    needsReload: ["TASK_NOT_FOUND", "TASK_STALE", "TASK_DONE"].includes(error.code),
  };
}
