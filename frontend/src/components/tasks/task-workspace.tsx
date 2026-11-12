"use client";

import { useCallback, useState, useSyncExternalStore } from "react";
import Link from "next/link";
import { Plus } from "lucide-react";
import { useClinic } from "@/components/panel/clinic-context";
import { useClinicResource } from "@/components/panel/clinic-resource";
import { PanelState } from "@/components/panel/panel-state";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { useLeadMutation } from "@/components/funnel/funnel-ui";
import { getDashboard } from "@/lib/api/reports";
import { contactPath } from "@/lib/api/contacts";
import { listTasks, taskProblem, taskTabs, type Task, type TaskTab } from "@/lib/api/tasks";
import { TaskDialog, type TaskAction, type TaskBinding } from "./task-dialog";
import { TaskPages } from "./task-pickers";

const notices = { create: "Tarefa criada.", edit: "Tarefa atualizada.", complete: "Tarefa concluída.", reopen: "Tarefa reaberta." };
const loadTimeZone = async (signal: AbortSignal) => (await getDashboard(signal)).timeZone;
const recordLink = "inline-flex min-h-11 items-center rounded-sm font-semibold text-primary underline-offset-4 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2";

function TaskRow({ task, formatter, binding, canWrite, canEdit, blocked, mine, open }: {
  task: Task; formatter: Intl.DateTimeFormat; binding?: TaskBinding; canWrite: boolean; canEdit: boolean; blocked: boolean;
  mine: boolean; open: (action: TaskAction) => void;
}) {
  const Heading = binding ? "h3" : "h2";
  return (
    <li className="flex flex-wrap items-start justify-between gap-x-5 gap-y-2 px-4 py-4">
      <div className="min-w-0 flex-1 basis-52 space-y-2">
        <div>
          <Heading className="font-semibold wrap-anywhere">{task.title}</Heading>
          <p className="mt-1 text-sm text-muted-foreground wrap-anywhere">
            Prazo <time dateTime={task.dueAt}>{formatter.format(new Date(task.dueAt))}</time> · {mine ? "Você" : "Outro integrante"}
          </p>
        </div>
        {task.description && <p className="text-sm whitespace-pre-wrap wrap-anywhere">{task.description}</p>}
        {task.completedAt && <p className="text-sm text-muted-foreground">
          Concluída em <time dateTime={task.completedAt}>{formatter.format(new Date(task.completedAt))}</time>
        </p>}
        {task.completionNote && <p className="text-sm whitespace-pre-wrap wrap-anywhere"><strong>Nota de conclusão</strong> · {task.completionNote}</p>}
        {(!binding || (!binding.leadId && task.leadId)) && (
          <div className="flex flex-wrap gap-x-4 text-sm">
            {!binding && <Link href={contactPath(task.contactId)} className={recordLink}>Abrir contato</Link>}
            {task.leadId && <Link href={`/app/funil/${task.leadId}`} className={recordLink}>Abrir lead</Link>}
          </div>
        )}
      </div>
      {canWrite && (
        <div className="flex flex-wrap gap-2">
          {task.status === "OPEN" ? (
            <>
              <Button type="button" variant="outline" className="min-h-11" disabled={!canEdit}
                aria-label={`Editar ${task.title}`} onClick={() => open({ kind: "edit", task })}>Editar</Button>
              <Button type="button" className="min-h-11" disabled={blocked} aria-label={`Concluir ${task.title}`}
                onClick={() => open({ kind: "complete", task })}>Concluir</Button>
            </>
          ) : <Button type="button" variant="outline" className="min-h-11" disabled={blocked} aria-label={`Reabrir ${task.title}`}
            onClick={() => open({ kind: "reopen", task })}>Reabrir</Button>}
        </div>
      )}
    </li>
  );
}

type WorkspaceProps = { initialTab?: TaskTab; binding?: TaskBinding };

function subscribeVisibility(onChange: () => void) {
  document.addEventListener("visibilitychange", onChange);
  return () => document.removeEventListener("visibilitychange", onChange);
}

export function TaskWorkspace(props: WorkspaceProps) {
  const mutation = useLeadMutation("Não foi possível confirmar a operação. Atualize as tarefas e confira o resultado antes de repetir.", taskProblem);
  const [revision, setRevision] = useState(0);
  const [notice, setNotice] = useState("");
  const visible = useSyncExternalStore(subscribeVisibility, () => document.visibilityState === "visible", () => false);
  function confirm(kind: TaskAction["kind"]) {
    setNotice(notices[kind]);
    setRevision((value) => value + 1);
  }
  return visible ? <TaskContent {...props} mutation={mutation} revision={revision} notice={notice} setNotice={setNotice} confirm={confirm} /> : null;
}

function TaskContent({ initialTab = "TODAY", binding, mutation, revision, notice, setNotice, confirm }: WorkspaceProps & {
  mutation: ReturnType<typeof useLeadMutation>; revision: number; notice: string;
  setNotice: (value: string) => void; confirm: (kind: TaskAction["kind"]) => void;
}) {
  const { clinic, subjectId } = useClinic();
  const [tab, setTab] = useState(initialTab);
  const [mine, setMine] = useState(false);
  const [page, setPage] = useState(1);
  const [action, setAction] = useState<TaskAction>();
  const contactId = binding?.contactId;
  const leadId = binding?.leadId;
  const canWrite = clinic.permissions.TASK_WRITE === "ALL";
  const load = useCallback((signal: AbortSignal) => listTasks({ tab, mine, page, contactId, leadId }, signal), [tab, mine, page, contactId, leadId]);
  const { result, reload } = useClinicResource(load, revision);
  const zone = useClinicResource(loadTimeZone);
  const timeZone = zone.result?.data;
  const formatter = new Intl.DateTimeFormat("pt-BR", { dateStyle: "short", timeStyle: "short", timeZone: timeZone ?? "UTC" });
  const data = result?.data;
  const error = result?.error;
  const canCreate = canWrite && !binding?.archived && (binding || clinic.permissions.CONTACT_READ === "ALL");
  const blocked = mutation.pending || Boolean(mutation.failure?.needsReload);

  function refresh() {
    setAction(undefined);
    mutation.clearFailure();
    reload();
  }

  function saved(kind: TaskAction["kind"]) {
    confirm(kind);
    if (kind !== "create" && page > 1 && data?.items.length === 1) setPage(page - 1);
    setAction(undefined);
  }

  function open(next: TaskAction) {
    if (blocked) return;
    mutation.clearFailure();
    setNotice("");
    setAction(next);
  }

  const list = (
    <>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div role="group" aria-label="Filtrar tarefas por prazo ou situação" className="flex flex-wrap gap-1 rounded-lg border bg-card p-1">
          {(Object.entries(taskTabs) as [TaskTab, string][]).map(([value, label]) => (
            <Button key={value} type="button" variant={tab === value ? "secondary" : "ghost"} aria-pressed={tab === value}
              className="min-h-11" onClick={() => { setTab(value); setPage(1); setNotice(""); }}>{label}</Button>
          ))}
        </div>
        <label className="flex min-h-11 cursor-pointer items-center gap-2 text-sm font-semibold">
          <input type="checkbox" className="size-4 accent-primary" checked={mine}
            onChange={(event) => { setMine(event.target.checked); setPage(1); setNotice(""); }} /> Minhas
        </label>
      </div>
      <p role="status" className={notice ? "rounded-lg border bg-card p-3 text-sm" : "sr-only"}>{notice}</p>
      {!action && mutation.pending && <p role="status" className="text-sm text-muted-foreground">Salvando tarefa…</p>}
      {!action && mutation.failure && <PanelState kind={mutation.failure.needsReload ? "conflict" : "error"}
        title="A alteração não foi confirmada" message={mutation.failure.message} onRetry={mutation.failure.needsReload ? refresh : undefined} />}
      {zone.result?.error ? (
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <p role="alert">Fuso da clínica indisponível. Os prazos estão em UTC e a edição de horários está pausada.</p>
          <Button type="button" variant="outline" className="min-h-11" onClick={zone.reload}>Tentar carregar o fuso</Button>
        </div>
      ) : <p className="text-sm text-muted-foreground">{timeZone ? <>Horários da clínica · <span translate="no">{timeZone}</span></> : "Carregando fuso da clínica…"}</p>}
      {!result && <PanelState kind="loading" title="Carregando tarefas…" />}
      {error && <PanelState kind={error.status === 403 ? "forbidden" : "error"} title="Não foi possível carregar as tarefas"
        message={taskProblem(error).message} onRetry={error.status === 403 ? undefined : reload} />}
      {data && (
        data.items.length ? (
          <ul className="divide-y rounded-lg border bg-card" aria-label="Tarefas">
            {data.items.map((task) => <TaskRow key={task.id} task={task} formatter={formatter} binding={binding}
              canWrite={canWrite} canEdit={Boolean(timeZone) && !blocked} blocked={blocked} mine={task.responsibleId === subjectId} open={open} />)}
          </ul>
        ) : <PanelState kind="empty" title="Nenhuma tarefa neste filtro" />
      )}
      {data && <TaskPages {...data} change={setPage} label="Páginas de tarefas" />}
      {action && canWrite && <TaskDialog action={action} binding={binding} timeZone={timeZone ?? "UTC"} mutation={mutation}
        close={() => { mutation.clearFailure(); setAction(undefined); }} reload={refresh} saved={saved} />}
    </>
  );

  const create = canCreate && <Button type="button" className="min-h-11 gap-2" disabled={!timeZone || !data || blocked}
    onClick={() => open({ kind: "create" })}><Plus size={17} aria-hidden="true" />Nova tarefa</Button>;

  return binding ? (
    <Card aria-label="Tarefas vinculadas">
      <CardHeader className="flex flex-wrap items-center justify-between gap-3 space-y-0"><CardTitle>Tarefas vinculadas</CardTitle>{create}</CardHeader>
      <CardContent className="space-y-3">{list}</CardContent>
    </Card>
  ) : (
    <>
      <header className="flex flex-wrap items-end justify-between gap-4">
        <h1 className="font-[family-name:var(--font-branly)] text-3xl font-extrabold tracking-tight sm:text-4xl">Tarefas</h1>
        {create}
      </header>
      {list}
    </>
  );
}
