"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { Activity, StickyNote } from "lucide-react";
import { useClinic } from "@/components/panel/clinic-context";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { funnelErrorMessage } from "@/lib/api/funnel-view";
import { leadHistoryEntries } from "@/lib/api/lead-history";
import { isOriginType, originTypeLabel } from "@/lib/lead-origin";
import { addLeadNote, getLossReasons, getPipeline, listLeadEvents, listLeadNotes, type LeadEvent } from "@/lib/api/leads";
import { fieldClass, temperatureLabel, useFunnelResource, useLeadMutation } from "./funnel-ui";

const dateTime = new Intl.DateTimeFormat("pt-BR", { dateStyle: "medium", timeStyle: "short", timeZone: "UTC" });
const noteLimit = 2000;

function eventCopy(event: LeadEvent, stages: Map<string, string>, reasons: Map<string, string>, subjectId: string) {
  const stage = (id: string | null) => id ? stages.get(id) ?? "Etapa indisponível" : null;
  const person = (id: string) => id === subjectId ? "Você" : id;
  const { fromValue, toValue } = event;

  switch (event.type) {
    case "CREATED":
      return { title: "Lead criado", detail: stage(toValue) };
    case "STAGE_CHANGED":
      return { title: "Etapa alterada", detail: `${stage(fromValue) ?? "Sem etapa"} → ${stage(toValue) ?? "Sem etapa"}` };
    case "TEMPERATURE_CHANGED":
      return {
        title: "Temperatura alterada",
        detail: toValue && Object.hasOwn(temperatureLabel, toValue)
          ? temperatureLabel[toValue as keyof typeof temperatureLabel] : "Sem temperatura",
      };
    case "RESPONSIBLE_CHANGED":
      return { title: "Responsável alterado", detail: toValue ? person(toValue) : "Sem responsável" };
    case "WON":
      return { title: "Lead ganho" };
    case "LOST":
      return { title: "Lead perdido", detail: reasons.get(event.lossReasonId ?? "") };
    case "ORIGIN_CHANGED":
      return {
        title: "Origem atualizada",
        detail: toValue && isOriginType(toValue)
          ? fromValue && isOriginType(fromValue) && fromValue !== toValue
            ? `${originTypeLabel(fromValue)} → ${originTypeLabel(toValue)}`
            : originTypeLabel(toValue)
          : null,
      };
    default:
      return { title: "Lead atualizado" };
  }
}

export function LeadHistory({ leadId, revision }: { leadId: string; revision: number }) {
  const { clinic, subjectId } = useClinic();
  const [draft, setDraft] = useState("");
  const [validation, setValidation] = useState("");
  const [notice, setNotice] = useState("");
  const field = useRef<HTMLTextAreaElement>(null);
  const canWrite = clinic.permissions.LEAD_WRITE === "ALL";
  const load = useCallback(async (signal: AbortSignal) => {
    const [events, notes, pipeline] = await Promise.all([
      listLeadEvents(leadId, signal), listLeadNotes(leadId, signal), getPipeline(signal),
    ]);
    const lossReasons = events.some(({ lossReasonId }) => lossReasonId)
      ? await getLossReasons(signal, true)
      : [];
    return {
      entries: leadHistoryEntries(events, notes),
      stages: new Map([...pipeline.stages, ...(pipeline.archivedStages ?? [])].map(({ id, name }) => [id, name])),
      reasons: new Map(lossReasons.map(({ id, name }) => [id, name])),
    };
  }, [leadId]);
  const { result, reload } = useFunnelResource(load, revision);
  const mutation = useLeadMutation("Não foi possível confirmar o envio. Atualize o histórico e verifique se a nota aparece antes de reenviar.");
  const data = result?.data;
  const error = result?.error;
  const fieldError = validation || (mutation.failure?.fields.includes("body") ? "Revise o texto da nota e tente novamente." : "");
  const hasDraft = Boolean(draft) && canWrite;
  useEffect(() => {
    if (!hasDraft) return;
    function warn(event: BeforeUnloadEvent) {
      event.preventDefault();
      event.returnValue = "";
    }
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [hasDraft]);

  function submitNote(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canWrite || !data || mutation.pending || mutation.failure?.needsReload) return;
    setNotice("");
    if (!draft.trim()) {
      setValidation("Escreva uma nota antes de adicionar.");
      field.current?.focus();
      return;
    }
    setValidation("");
    void mutation.run(() => addLeadNote(leadId, draft, subjectId), () => {
      setDraft("");
      setNotice("Nota adicionada.");
      reload();
      field.current?.focus();
    });
  }

  if (error?.status === 404) return null;

  return (
    <Card aria-labelledby="lead-history-title">
      <CardHeader>
        <CardTitle id="lead-history-title" className="flex items-center gap-2 text-balance">
          <Activity className="size-4 text-primary" aria-hidden="true" /> Histórico do lead
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-5">
        {!result && <p role="status" className="text-sm text-muted-foreground">Carregando histórico…</p>}
        {error && (
          <div className="space-y-2">
            <p role="alert" className="text-sm text-destructive">
              {error.status === 403 ? "Você não tem permissão para consultar o histórico." : funnelErrorMessage(error.code, "Não foi possível carregar o histórico.")}
            </p>
            {error.status !== 403 && <Button type="button" variant="outline" className="min-h-11" onClick={reload}>Tentar novamente</Button>}
          </div>
        )}
        {data && (data.entries.length ? (
          <ol aria-label="Eventos e notas em ordem cronológica" className="ms-2 border-s border-border">
            {data.entries.map((entry) => {
              const isNote = entry.kind === "note";
              const Icon = isNote ? StickyNote : Activity;
              const { title, detail } = isNote
                ? { title: "Nota adicionada", detail: entry.note.body }
                : eventCopy(entry.event, data.stages, data.reasons, subjectId);
              return (
                <li key={`${entry.kind}-${isNote ? entry.note.id : entry.event.id}`} className="relative space-y-1 pb-5 ps-5 last:pb-0">
                  <span className="absolute start-0 top-0 -translate-x-1/2 rounded-full border bg-card p-1 text-primary">
                    <Icon className="size-3" aria-hidden="true" />
                  </span>
                  <p className="text-sm font-semibold">{title}</p>
                  <p className="text-xs text-muted-foreground wrap-anywhere">
                    <time dateTime={entry.at}>{dateTime.format(new Date(entry.at))} UTC</time>
                    {" · "}<span translate="no">{entry.actorId === subjectId ? "Você" : entry.actorId ?? "Autor indisponível"}</span>
                  </p>
                  {detail && <p className="mt-2 whitespace-pre-wrap rounded-md bg-muted/60 px-3 py-2 text-sm wrap-anywhere">{detail}</p>}
                </li>
              );
            })}
          </ol>
        ) : <p role="status" className="text-sm text-muted-foreground">Nenhum evento ou nota registrado.</p>)}

        {canWrite && error?.status !== 403 && (
          <form method="post" onSubmit={submitNote} className="space-y-3 border-t border-border pt-4">
            <div>
              <label htmlFor="lead-note" className="text-sm font-semibold">Nova nota</label>
              <textarea id="lead-note" name="body" ref={field} rows={3} required maxLength={noteLimit} autoComplete="off"
                className={`${fieldClass} block resize-y`} value={draft} readOnly={mutation.pending}
                aria-invalid={Boolean(fieldError)} aria-describedby={fieldError ? "lead-note-help lead-note-error" : "lead-note-help"}
                onChange={(event) => {
                  setDraft(event.target.value);
                  setValidation("");
                  setNotice("");
                  if (!mutation.failure?.needsReload) mutation.clearFailure();
                }} />
              <p id="lead-note-help" className="mt-1 text-xs text-muted-foreground">
                Até 2.000 caracteres. Registre informações do atendimento sem incluir dados de saúde.
              </p>
              {fieldError && <p id="lead-note-error" role="alert" className="mt-1 text-sm text-destructive">{fieldError}</p>}
            </div>
            {mutation.failure && !fieldError && <p role="alert" className="text-sm text-destructive">{mutation.failure.message}</p>}
            <div className="flex flex-wrap items-center gap-3">
              <Button type="submit" className="min-h-11 touch-manipulation" disabled={!data || mutation.pending || mutation.failure?.needsReload}>
                {mutation.pending ? "Adicionando…" : "Adicionar nota"}
              </Button>
              {mutation.failure?.needsReload && (
                <Button type="button" variant="outline" className="min-h-11" onClick={() => { mutation.clearFailure(); reload(); }}>
                  Atualizar histórico
                </Button>
              )}
            </div>
          </form>
        )}
        <p role="status" className="empty:hidden text-sm text-muted-foreground">{mutation.pending ? "Enviando nota…" : notice}</p>
      </CardContent>
    </Card>
  );
}
