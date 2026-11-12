"use client";

import { useId, useState, type FormEvent } from "react";
import { Dialog } from "@base-ui/react/dialog";
import { fieldClass, useLeadMutation } from "@/components/funnel/funnel-ui";
import { useClinic } from "@/components/panel/clinic-context";
import { Button } from "@/components/ui/button";
import { changeTaskStatus, createTask, editTask, taskTitles, type Task } from "@/lib/api/tasks";
import { taskDeadlineInstant, taskLocalDeadline } from "@/lib/task-deadline";
import { ContactPicker, LeadPicker, ResponsiblePicker } from "./task-pickers";

export type TaskAction = { kind: "create" } | { kind: "edit" | "complete" | "reopen"; task: Task };
export type TaskBinding = { contactId: string; contactName?: string; leadId?: string; archived?: boolean };
const privacyHint = "Evite incluir dados de saúde ou informações clínicas.";

export function TaskDialog({ action, binding, timeZone, mutation, close, reload, saved }: {
  action: TaskAction; binding?: TaskBinding; timeZone: string;
  mutation: ReturnType<typeof useLeadMutation>;
  close: () => void; reload: () => void; saved: (action: TaskAction["kind"]) => void;
}) {
  const { clinic, subjectId } = useClinic();
  const formId = useId();
  const hintId = `${formId}-privacy`;
  const errorId = `${formId}-error`;
  const zoneId = `${formId}-zone`;
  const task = action.kind === "create" ? undefined : action.task;
  const editFields = action.kind === "create" || action.kind === "edit";
  const canChooseMember = clinic.permissions.TEAM_VIEW === "ALL";
  const [contact, setContact] = useState({ id: binding?.contactId ?? "", label: binding?.contactName ?? "" });
  const [lead, setLead] = useState({ id: binding?.leadId ?? "", label: "" });
  const initialResponsible = task?.responsibleId ?? subjectId;
  const [responsible, setResponsible] = useState({ id: initialResponsible, label: initialResponsible === subjectId ? "Você" : "Responsável atual" });
  const [validation, setValidation] = useState("");
  const locked = mutation.pending || Boolean(mutation.failure?.needsReload);
  const initialDeadline = task ? taskLocalDeadline(task.dueAt, timeZone) : "";

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (locked || clinic.permissions.TASK_WRITE !== "ALL") return;
    setValidation("");
    const fields = new FormData(event.currentTarget);
    const text = (key: string) => String(fields.get(key) ?? "").trim();
    const done = () => saved(action.kind);
    if (action.kind === "complete" || action.kind === "reopen") {
      const { task, kind } = action;
      void mutation.run(() => changeTaskStatus(task, kind, text("note") || null), done);
      return;
    }
    const title = text("title");
    const local = text("dueAt");
    const dueAt = task && local === initialDeadline ? task.dueAt : taskDeadlineInstant(local, timeZone);
    if (!title || !dueAt || !responsible.id || (action.kind === "create" && !contact.id)) {
      setValidation(!title ? "Informe o título da tarefa." : !dueAt
        ? "Informe uma data e hora válidas no fuso da clínica. Se houver mudança de horário, escolha um horário fora da transição."
        : !responsible.id ? "Selecione um responsável." : "Selecione um contato ativo.");
      const field = !title ? "title" : !dueAt ? "dueAt" : null;
      if (field) (event.currentTarget.elements.namedItem(field) as HTMLElement | null)?.focus();
      return;
    }
    const draft = { title, dueAt, description: text("description") || null, responsibleId: responsible.id };
    void mutation.run(() => task ? editTask(task, draft) : createTask({ ...draft, contactId: contact.id, leadId: lead.id || null }), done);
  }

  function dismiss() {
    if (mutation.pending) return;
    if (mutation.failure?.needsReload) reload();
    else close();
  }

  return (
    <Dialog.Root open onOpenChange={(open) => { if (!open) dismiss(); }}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-slate-950/55" />
        <Dialog.Popup className="fixed top-1/2 left-1/2 z-50 max-h-[90dvh] w-[calc(100%-2rem)] max-w-lg -translate-x-1/2 -translate-y-1/2 space-y-3 overflow-y-auto overscroll-contain rounded-xl border bg-card p-5 shadow-xl">
          <Dialog.Title className="text-xl font-bold">{taskTitles[action.kind]}</Dialog.Title>
          <Dialog.Description className="text-sm text-muted-foreground wrap-anywhere">
            {task?.title ?? (binding?.contactName ? `Contato vinculado · ${binding.contactName}` : "Organize o próximo atendimento de um contato.")}
            {action.kind === "create" && binding?.leadId && " · Lead atual vinculado"}
          </Dialog.Description>
          <form method="post" onSubmit={submit} className="space-y-4">
            {editFields && (
              <>
                <label className="block text-sm font-semibold">Título
                  <input name="title" defaultValue={task?.title} maxLength={200} required readOnly={locked} className={fieldClass}
                    aria-invalid={mutation.failure?.fields.includes("title")} aria-describedby={mutation.failure?.fields.includes("title") ? errorId : undefined} />
                </label>
                <label className="block text-sm font-semibold">Prazo
                  <input name="dueAt" type="datetime-local" defaultValue={initialDeadline} required readOnly={locked} className={fieldClass}
                    aria-describedby={zoneId} aria-invalid={mutation.failure?.fields.includes("dueAt")} />
                </label>
                <p id={zoneId} className="text-xs text-muted-foreground">Horário da clínica · <span translate="no">{timeZone}</span></p>
                {action.kind === "create" && !binding && (
                  <ContactPicker value={contact} disabled={locked} onSelect={(choice) => { setContact(choice); setLead({ id: "", label: "" }); }} />
                )}
                {action.kind === "create" && contact.id && !binding?.leadId && clinic.permissions.LEAD_READ === "ALL" && (
                  <LeadPicker key={contact.id} contactId={contact.id} timeZone={timeZone} value={lead} onSelect={setLead} disabled={locked} />
                )}
                {canChooseMember ? <ResponsiblePicker subjectId={subjectId} value={responsible} onSelect={setResponsible} disabled={locked} />
                  : initialResponsible !== subjectId ? (
                    <label className="block text-sm font-semibold">Responsável
                      <select className={fieldClass} disabled={locked} value={responsible.id} onChange={(event) => setResponsible({
                        id: event.target.value, label: event.target.value === subjectId ? "Você" : "Responsável atual",
                      })}>
                        <option value={initialResponsible}>Manter responsável atual</option>
                        <option value={subjectId}>Você</option>
                      </select>
                    </label>
                  ) : <p className="text-sm"><strong>Responsável</strong> · Você</p>}
                <label className="block text-sm font-semibold">Descrição (opcional)
                  <textarea name="description" rows={3} maxLength={2000} defaultValue={task?.description ?? ""} readOnly={locked}
                    className={fieldClass} aria-describedby={hintId} />
                </label>
                <p id={hintId} className="text-xs text-muted-foreground">{privacyHint}</p>
              </>
            )}
            {action.kind === "complete" && (
              <>
                <label className="block text-sm font-semibold">Nota de conclusão (opcional)
                  <textarea name="note" rows={3} maxLength={500} readOnly={locked} className={fieldClass} aria-describedby={hintId} />
                </label>
                <p id={hintId} className="text-xs text-muted-foreground">{privacyHint}</p>
              </>
            )}
            {action.kind === "reopen" && <p className="text-sm text-muted-foreground">A tarefa volta para a lista de abertas com o prazo atual.</p>}
            {(validation || mutation.failure) && <p id={errorId} role="alert" className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive">{validation || mutation.failure?.message}</p>}
            <div className="flex flex-wrap justify-end gap-2">
              <Dialog.Close render={<Button type="button" variant="outline" className="min-h-11" disabled={mutation.pending} />}>Cancelar</Dialog.Close>
              {mutation.failure?.needsReload
                ? <Button type="button" className="min-h-11" onClick={reload}>Atualizar tarefas</Button>
                : <Button type="submit" className="min-h-11" disabled={locked}>{mutation.pending ? "Salvando…" : editFields ? "Salvar tarefa" : taskTitles[action.kind]}</Button>}
            </div>
          </form>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
