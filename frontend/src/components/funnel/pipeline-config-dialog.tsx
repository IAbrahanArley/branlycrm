"use client";

import { useEffect, useRef, useState, type FormEvent, type PointerEvent } from "react";
import { Dialog } from "@base-ui/react/dialog";
import { ArrowDown, ArrowUp, GripVertical } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { ApiError } from "@/lib/api/client";
import type { LossReason, Pipeline, Stage } from "@/lib/api/leads";
import { moveStageInOrder } from "@/lib/stage-order";
import { fieldClass, useLeadMutation } from "./funnel-ui";

export type ConfigUpdate = Pipeline | LossReason;

type ConfigAction =
  | { kind: "name"; name?: string; maxLength: number; save: (name: string) => Promise<ConfigUpdate> }
  | { kind: "archive"; archived: boolean; save: () => Promise<ConfigUpdate> }
  | { kind: "order"; stages: Stage[]; save: (ids: string[]) => Promise<Pipeline> };

export type ConfigDialogAction = ConfigAction & { title: string; description: string; success: string };

const messages = new Map([
  ["PIPELINE_STALE", "O funil mudou enquanto você editava. Confira a configuração atualizada e tente novamente."],
  ["STAGE_ORDER_MISMATCH", "A lista de etapas mudou. Recarregue a configuração antes de reordenar."],
  ["STAGE_NOT_FOUND", "Esta etapa não está disponível nesta clínica. Recarregue a configuração."],
  ["STAGE_DUPLICATE", "Já existe uma etapa ativa com esse nome."],
  ["STAGE_LIMIT", "A clínica atingiu o limite de 12 etapas abertas. Arquive uma etapa para liberar espaço."],
  ["STAGE_PROTECTED", "A etapa de entrada e as etapas de encerramento são protegidas."],
  ["STAGE_HAS_OPEN_LEADS", "Mova os leads em aberto para outra etapa antes de arquivar esta."],
  ["LOSS_REASON_STALE", "Outra pessoa alterou este motivo. Recarregue a configuração para continuar."],
  ["LOSS_REASON_NOT_FOUND", "Este motivo não está disponível nesta clínica. Recarregue a configuração."],
  ["LOSS_REASON_DUPLICATE", "Já existe um motivo com esse nome. Se estiver arquivado, você pode desarquivá-lo."],
  ["LOSS_REASON_LIMIT", "A clínica atingiu o limite de 50 motivos de perda."],
  ["LOSS_REASON_LAST_ACTIVE", "Mantenha pelo menos um motivo de perda ativo."],
  ["INVALID_INPUT", "Revise os dados informados e tente novamente."],
  ["CSRF_INVALID", "A confirmação de segurança expirou. Tente salvar novamente."],
]);

function feedback(error: ApiError) {
  const needsReload = error.status === 404 || ["PIPELINE_STALE", "STAGE_ORDER_MISMATCH", "LOSS_REASON_STALE"].includes(error.code);
  return {
    message: messages.get(error.code) ?? (error.status === 403 ? "Você não tem permissão para fazer esta alteração." : "Não foi possível salvar a configuração."),
    needsReload,
  };
}

export function PipelineConfigDialog({ action, onClose, onReload, onSaved }: {
  action: ConfigDialogAction; onClose: () => void; onReload: (message?: string) => void;
  onSaved: (update: ConfigUpdate, message: string) => void;
}) {
  const mutation = useLeadMutation("Não foi possível confirmar a alteração. Recarregue a configuração e confira o resultado antes de repetir.", feedback);
  const [ordered, setOrdered] = useState<readonly Stage[]>(action.kind === "order" ? action.stages : []);
  const dragSource = useRef<string | null>(null);
  const [dropTarget, setDropTarget] = useState<number>();
  const [orderNotice, setOrderNotice] = useState("");
  const [validation, setValidation] = useState("");
  const nameField = useRef<HTMLInputElement>(null);
  const problem = mutation.failure;
  const duplicate = problem?.message === messages.get("STAGE_DUPLICATE") || problem?.message === messages.get("LOSS_REASON_DUPLICATE");
  const nameInvalid = action.kind === "name" && (validation || duplicate || problem?.fields.includes("name"));
  const locked = mutation.pending || Boolean(problem?.needsReload);
  const changed = action.kind !== "order" || ordered.some((stage, index) => stage.id !== action.stages[index].id);

  useEffect(() => {
    if (nameInvalid && !mutation.pending) nameField.current?.focus();
  }, [nameInvalid, mutation.pending]);

  useEffect(() => {
    if (problem?.code === "PIPELINE_STALE") onReload(problem.message);
  }, [onReload, problem]);

  function move(from: number, to: number) {
    if (locked) return;
    const updated = moveStageInOrder(ordered, from, to);
    if (updated === ordered) return;
    setOrdered(updated);
    setOrderNotice(`${updated[to].name}, posição ${to + 1} de ${updated.length}.`);
  }

  function startDrag(event: PointerEvent<HTMLSpanElement>, index: number) {
    if (locked || dragSource.current !== null || !event.isPrimary || event.button !== 0) return;
    event.preventDefault();
    dragSource.current = ordered[index].id;
    event.currentTarget.setPointerCapture(event.pointerId);
  }

  function targetIndex(event: PointerEvent<HTMLSpanElement>) {
    const row = document.elementFromPoint(event.clientX, event.clientY)?.closest<HTMLElement>("[data-stage-order-index]");
    const list = event.currentTarget.closest("ol");
    return row && list?.contains(row) ? Number(row.dataset.stageOrderIndex) : undefined;
  }

  function finishDrag(event: PointerEvent<HTMLSpanElement>) {
    if (!event.currentTarget.hasPointerCapture(event.pointerId)) return;
    const target = targetIndex(event);
    if (dragSource.current !== null && target !== undefined) {
      move(ordered.findIndex(({ id }) => id === dragSource.current), target);
    }
    endDrag();
  }

  function endDrag() {
    dragSource.current = null;
    setDropTarget(undefined);
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (locked || !changed) return;
    const done = (update: ConfigUpdate) => onSaved(update, action.success);
    if (action.kind === "order") {
      void mutation.run(() => action.save(ordered.map(({ id }) => id)), done);
    } else if (action.kind === "archive") {
      void mutation.run(action.save, done);
    } else {
      const name = String(new FormData(event.currentTarget).get("name") ?? "").trim();
      if (!name) {
        setValidation("Informe um nome.");
        return;
      }
      void mutation.run(() => action.save(name), done);
    }
  }

  const submitLabel = action.kind === "archive" ? (action.archived ? "Confirmar arquivamento" : "Desarquivar")
    : action.kind === "order" ? "Salvar ordem" : "Salvar";

  return (
    <Dialog.Root open onOpenChange={(open) => {
      if (!open && !mutation.pending) {
        if (problem?.needsReload) onReload(problem.message);
        else onClose();
      }
    }}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-slate-950/55" />
        <Dialog.Popup className="fixed top-1/2 left-1/2 z-50 max-h-[90dvh] w-[calc(100%-2rem)] max-w-lg -translate-x-1/2 -translate-y-1/2 space-y-3 overflow-y-auto overscroll-contain rounded-xl border bg-card p-5 shadow-xl">
          <Dialog.Title className="text-xl font-bold text-balance">{action.title}</Dialog.Title>
          <Dialog.Description className="text-sm text-muted-foreground wrap-anywhere">{action.description}</Dialog.Description>
          <form method="post" onSubmit={submit} className="space-y-4">
            {action.kind === "name" && (
              <label className="block text-sm font-semibold">Nome
                <input name="name" ref={nameField} className={`${fieldClass} aria-invalid:border-destructive`} defaultValue={action.name}
                  maxLength={action.maxLength} required autoComplete="off" readOnly={locked}
                  aria-invalid={Boolean(nameInvalid)} aria-describedby={nameInvalid ? "config-problem" : undefined}
                  onChange={() => { setValidation(""); if (!problem?.needsReload) mutation.clearFailure(); }} />
              </label>
            )}
            {action.kind === "order" && (
              <>
                <ol className="divide-y rounded-lg border" aria-label="Ordem das etapas abertas">
                  {ordered.map((stage, index) => (
                    <li key={stage.id} data-stage-order-index={index}
                      className={`flex items-center gap-2 px-3 py-2 ${dropTarget === index ? "bg-primary/10 outline-2 outline-inset outline-primary" : ""}`}>
                      <span className="grid size-9 shrink-0 cursor-grab touch-none select-none place-items-center text-muted-foreground active:cursor-grabbing"
                        aria-hidden="true" onPointerDown={(event) => startDrag(event, index)}
                        onPointerMove={(event) => {
                          if (event.currentTarget.hasPointerCapture(event.pointerId)) setDropTarget(targetIndex(event));
                        }}
                        onPointerUp={finishDrag} onLostPointerCapture={endDrag}>
                        <GripVertical className="size-5" />
                      </span>
                      <span className="w-6 shrink-0 text-sm text-muted-foreground tabular-nums">{index + 1}</span>
                      <span className="min-w-0 flex-1 text-sm font-semibold wrap-anywhere">{stage.name}</span>
                      <Button type="button" size="icon" variant="outline" className="size-11 shrink-0 aria-disabled:opacity-45" aria-label={`Subir ${stage.name}`}
                        aria-disabled={index === 0 || locked} onClick={() => move(index, index - 1)}><ArrowUp aria-hidden="true" /></Button>
                      <Button type="button" size="icon" variant="outline" className="size-11 shrink-0 aria-disabled:opacity-45" aria-label={`Descer ${stage.name}`}
                        aria-disabled={index === ordered.length - 1 || locked} onClick={() => move(index, index + 1)}><ArrowDown aria-hidden="true" /></Button>
                    </li>
                  ))}
                </ol>
                <p role="status" className="sr-only">{orderNotice}</p>
              </>
            )}
            {(validation || problem) && <p id="config-problem" role="alert" className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive">{validation || problem?.message}</p>}
            <div className="flex flex-wrap justify-end gap-2">
              <Dialog.Close render={<Button type="button" variant="outline" className="min-h-11" disabled={mutation.pending} />}>Cancelar</Dialog.Close>
              {problem?.needsReload ? (
                <Button type="button" className="min-h-11" onClick={() => onReload(problem.message)}>Recarregar configuração</Button>
              ) : (
                <Button type="submit" className="min-h-11" variant={action.kind === "archive" && action.archived ? "destructive" : "default"}
                  disabled={mutation.pending || !changed}>{mutation.pending ? "Salvando…" : submitLabel}</Button>
              )}
            </div>
          </form>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
