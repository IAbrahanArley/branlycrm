"use client";

import { useId, useState, type FormEvent } from "react";
import { Dialog } from "@base-ui/react/dialog";
import { useClinic } from "@/components/panel/clinic-context";
import { Button } from "@/components/ui/button";
import type { ApiError } from "@/lib/api/client";
import { correctLeadOrigin, openLead, temperatures, type LeadDetail, type Temperature } from "@/lib/api/leads";
import { isOriginType, originTypeLabel, originTypes, parseOriginCode, type OriginDraft } from "@/lib/lead-origin";
import { fieldClass, temperatureLabel, useLeadMutation } from "./funnel-ui";

type Action = { kind: "create"; contactId: string; contactName: string } | { kind: "edit"; lead: LeadDetail };
type Field = "type" | "code" | "title";

function originFeedback(error: ApiError) {
  const messages = new Map([
    ["LEAD_ALREADY_OPEN", "Este contato já tem um lead aberto. Atualize o perfil para acessá-lo."],
    ["LEAD_CONTACT_UNAVAILABLE", "O contato está arquivado ou indisponível. Atualize o perfil."],
    ["LEAD_STALE", "O lead mudou desde a última consulta. Atualize o detalhe e revise a origem antes de salvar."],
    ["LEAD_NOT_FOUND", "O lead está indisponível nesta clínica."],
    ["INVALID_INPUT", "Revise os campos indicados antes de salvar."],
    ["CSRF_INVALID", "A sessão precisa de uma nova confirmação. Tente novamente."],
  ]);
  return {
    message: messages.get(error.code) ?? (error.status === 403 ? "Sua conta não tem permissão para esta alteração." : "Não foi possível salvar a origem."),
    needsReload: error.status === 404 || ["LEAD_ALREADY_OPEN", "LEAD_CONTACT_UNAVAILABLE", "LEAD_STALE"].includes(error.code),
  };
}

export function useOriginMutation() {
  return useLeadMutation("Não foi possível confirmar o envio. Atualize os dados e verifique o resultado antes de repetir.", originFeedback);
}

export function OriginDialog({ action, mutation, close, reload, saved }: {
  action: Action; mutation: ReturnType<typeof useOriginMutation>;
  close: () => void; reload: () => void; saved: (lead: LeadDetail) => void;
}) {
  const { clinic } = useClinic();
  const origin = action.kind === "edit" ? action.lead.origin : undefined;
  const [type, setType] = useState<string>(origin?.type ?? "");
  const [validation, setValidation] = useState<{ field: Field; message: string }>();
  const canEditOrigin = clinic.permissions.LEAD_ORIGIN_EDIT === "ALL";
  const locked = mutation.pending || Boolean(mutation.failure?.needsReload);
  const prefix = useId();
  const errorId = `${prefix}-error`;
  const codeHint = `${prefix}-code-hint`;

  function invalid(field: Field) {
    return validation?.field === field || Boolean(mutation.failure?.fields.includes(field));
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const permission = action.kind === "create" ? "LEAD_WRITE" : "LEAD_ORIGIN_EDIT";
    if (locked || clinic.permissions[permission] !== "ALL") return;
    const form = event.currentTarget;
    const fields = new FormData(form);
    const code = parseOriginCode(String(fields.get("code") ?? ""));
    const title = String(fields.get("title") ?? "").trim() || null;
    const temp = String(fields.get("temperature") ?? "");
    const temperature = temperatures.find((value): value is Temperature => value === temp);
    let draft: OriginDraft | undefined;
    if (canEditOrigin && (type || action.kind === "edit")) {
      if (!isOriginType(type)) {
        setValidation({ field: "type", message: "Selecione o tipo de origem." });
        (form.elements.namedItem("type") as HTMLSelectElement | null)?.focus();
        return;
      }
      if (code === undefined) {
        setValidation({ field: "code", message: "Use até 32 letras de A a Z, números, hífen ou sublinhado." });
        (form.elements.namedItem("code") as HTMLInputElement | null)?.focus();
        return;
      }
      draft = { type, code, title };
    }
    setValidation(undefined);
    if (action.kind === "create") void mutation.run(() => openLead(action.contactId, draft, temperature), saved);
    else if (draft) {
      const update = draft;
      void mutation.run(() => correctLeadOrigin(action.lead, update), saved);
    }
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
          <Dialog.Title className="text-xl font-bold">{action.kind === "create" ? "Novo lead" : "Corrigir origem"}</Dialog.Title>
          <Dialog.Description className="text-sm text-muted-foreground wrap-anywhere">
            {action.kind === "create" ? `Contato vinculado · ${action.contactName}` : "A origem será registrada como manual e a alteração aparecerá no histórico do lead."}
          </Dialog.Description>
          <form method="post" onSubmit={submit} className="space-y-4">
            {canEditOrigin && <label className="block text-sm font-semibold">Tipo de origem{action.kind === "create" && " (opcional)"}
              <select name="type" value={type} className={fieldClass} disabled={locked} aria-invalid={invalid("type")}
                aria-describedby={invalid("type") ? errorId : undefined} onChange={(event) => {
                  setType(event.target.value);
                  setValidation(undefined);
                  mutation.clearFailure();
                }}>
                {action.kind === "create" && <option value="">Não informar</option>}
                {originTypes.map((value) => <option key={value} value={value}>{originTypeLabel(value)}</option>)}
              </select>
            </label>}
            {canEditOrigin && type && (
              <>
                <label className="block text-sm font-semibold">Código (opcional)
                  <input name="code" className={fieldClass} defaultValue={origin?.code ?? ""} maxLength={32} readOnly={locked}
                    autoComplete="off" spellCheck={false} aria-invalid={invalid("code")} aria-describedby={invalid("code") ? `${codeHint} ${errorId}` : codeHint} />
                </label>
                <p id={codeHint} className="text-xs text-muted-foreground">Até 32 letras de A a Z, números, hífen ou sublinhado.</p>
                <label className="block text-sm font-semibold">Título da origem (opcional)
                  <input name="title" className={fieldClass} defaultValue={origin?.title ?? ""} maxLength={200} readOnly={locked}
                    autoComplete="off" aria-invalid={invalid("title")} aria-describedby={invalid("title") ? errorId : undefined} />
                </label>
              </>
            )}
            {action.kind === "create" && (
              <label className="block text-sm font-semibold">Temperatura (opcional)
                <select name="temperature" className={fieldClass} defaultValue="" disabled={locked}>
                  <option value="">Sem temperatura</option>
                  {temperatures.map((value) => <option key={value} value={value}>{temperatureLabel[value]}</option>)}
                </select>
              </label>
            )}
            {(validation || mutation.failure) && <p id={errorId} role="alert" className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive">{validation?.message ?? mutation.failure?.message}</p>}
            <div className="flex flex-wrap justify-end gap-2">
              <Dialog.Close render={<Button type="button" variant="outline" className="min-h-11" disabled={mutation.pending} />}>Cancelar</Dialog.Close>
              {mutation.failure?.needsReload
                ? <Button type="button" className="min-h-11" onClick={reload}>Atualizar dados</Button>
                : <Button type="submit" className="min-h-11" disabled={locked}>{mutation.pending ? "Salvando…" : action.kind === "create" ? "Criar lead" : "Salvar origem"}</Button>}
            </div>
          </form>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
