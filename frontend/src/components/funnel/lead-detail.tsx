"use client";

import { useCallback, useState, type FormEvent } from "react";
import Link from "next/link";
import { ArrowLeft, ReceiptText } from "lucide-react";
import { useClinic } from "@/components/panel/clinic-context";
import { PanelState } from "@/components/panel/panel-state";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { contactPath, getContact, isContactId } from "@/lib/api/contacts";
import { getLead, getLossReasons, getPipeline, loseLead, moveLead } from "@/lib/api/leads";
import { fieldClass, FunnelHeading, FunnelProblem, statusLabel, TemperatureBadge, useFunnelResource, useLeadMutation } from "./funnel-ui";
import { RegisterSaleDialog } from "./register-sale-dialog";

const dateTime = new Intl.DateTimeFormat("pt-BR", { dateStyle: "medium", timeStyle: "short", timeZone: "UTC" });

function LeadDetail({ leadId }: { leadId: string }) {
  const { clinic, subjectId } = useClinic();
  const [notice, setNotice] = useState("");
  const [recordLoss, setRecordLoss] = useState(false);
  const [registeringSale, setRegisteringSale] = useState(false);
  const canRegisterOutcome = clinic.permissions.SALE_REGISTER === "ALL";
  const canReadContact = clinic.permissions.CONTACT_READ === "ALL";
  const load = useCallback(async (signal: AbortSignal) => {
    const [lead, pipeline] = await Promise.all([getLead(leadId, signal), getPipeline(signal)]);
    const [contact, reasons] = await Promise.all([
      canReadContact ? getContact(lead.contactId, signal).then(({ id, name, phone }) => ({ id, name, phone })) : null,
      lead.status === "LOST" || (lead.status === "OPEN" && canRegisterOutcome)
        ? getLossReasons(signal, lead.status === "LOST")
        : [],
    ]);
    return { lead, pipeline, contact, reasons };
  }, [canReadContact, canRegisterOutcome, leadId]);
  const { result, reload } = useFunnelResource(load);
  const mutation = useLeadMutation();
  const data = result?.data;
  if (!data) {
    return (
      <>
        <FunnelHeading title="Detalhe do lead" />
        {result?.error ? <FunnelProblem error={result.error} retry={reload} /> : <PanelState kind="loading" title="Carregando lead…" />}
      </>
    );
  }

  const { lead, pipeline, contact, reasons } = data;
  const stage = pipeline.stages.find((candidate) => candidate.id === lead.stageId);
  const destinations = pipeline.stages.filter((candidate) => candidate.kind === "OPEN" && candidate.id !== lead.stageId);
  const allowMove = lead.status === "OPEN" && clinic.permissions.LEAD_WRITE === "ALL" && destinations.length > 0;
  const allowOutcome = lead.status === "OPEN" && canRegisterOutcome;
  const disabled = mutation.pending || Boolean(mutation.failure?.needsReload);
  const lossReason = reasons.find((reason) => reason.id === lead.lossReasonId);

  function submitMove(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const stageId = new FormData(event.currentTarget).get("stageId");
    const destination = destinations.find(({ id }) => id === stageId);
    if (!allowMove || !destination) return;
    setNotice("");
    void mutation.run(() => moveLead(lead, destination.id), () => { setNotice(`Lead movido para ${destination.name}.`); reload(); });
  }

  function submitLoss(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const lossReasonId = new FormData(event.currentTarget).get("lossReasonId");
    const reason = reasons.find(({ id, archived }) => !archived && id === lossReasonId);
    if (!allowOutcome || !reason) return;
    setNotice("");
    void mutation.run(() => loseLead(lead, reason.id), () => {
      setRecordLoss(false);
      setNotice("Perda registrada.");
      reload();
    });
  }

  return (
    <>
      <FunnelHeading title={contact?.name ?? "Detalhe do lead"} description="Consulte o atendimento e registre a próxima etapa da oportunidade.">
        {allowOutcome && <Button type="button" className="min-h-11 gap-2" disabled={disabled}
          onClick={() => { mutation.clearFailure(); setNotice(""); setRecordLoss(false); setRegisteringSale(true); }}>
          <ReceiptText aria-hidden="true" />Registrar venda
        </Button>}
      </FunnelHeading>
      {notice && <p role="status" className="rounded-lg border bg-white p-3 text-sm">{notice}</p>}
      {mutation.failure && !registeringSale && <PanelState kind="conflict" title="A alteração não foi confirmada" message={mutation.failure.message}
        onRetry={() => { mutation.clearFailure(); setRecordLoss(false); reload(); }} />}
      <Card aria-labelledby="lead-summary">
        <CardHeader><CardTitle id="lead-summary">Resumo da oportunidade</CardTitle></CardHeader>
        <CardContent className="space-y-4">
          <dl className="grid gap-4 text-sm sm:grid-cols-2">
            <div><dt className="text-slate-600">Etapa</dt><dd className="font-semibold">{stage?.name ?? "Etapa indisponível"}</dd></div>
            <div><dt className="text-slate-600">Situação</dt><dd className="font-semibold">{statusLabel[lead.status]}</dd></div>
            <div><dt className="text-slate-600">Temperatura</dt><dd className="mt-1"><TemperatureBadge temperature={lead.temperature} /></dd></div>
            <div><dt className="text-slate-600">Responsável</dt><dd className="font-semibold">{lead.responsibleId === subjectId ? "Você" : lead.responsibleId ? "Membro da equipe" : "Sem responsável"}</dd></div>
            <div><dt className="text-slate-600">Abertura</dt><dd className="font-semibold">{dateTime.format(new Date(lead.openedAt))} UTC</dd></div>
            {lead.closedAt && <div><dt className="text-slate-600">Encerramento</dt><dd className="font-semibold">{dateTime.format(new Date(lead.closedAt))} UTC</dd></div>}
            {lossReason && <div><dt className="text-slate-600">Motivo da perda</dt><dd className="font-semibold wrap-anywhere">{lossReason.name}</dd></div>}
            {contact && <div><dt className="text-slate-600">Telefone</dt><dd className="font-semibold wrap-anywhere">{contact.phone}</dd></div>}
          </dl>
          {contact && <Link href={contactPath(contact.id)} className="inline-flex min-h-11 items-center rounded-sm text-sm font-semibold text-[#143e75] underline-offset-4 hover:underline focus-visible:outline-2">Abrir perfil do contato</Link>}
        </CardContent>
      </Card>
      {(allowMove || allowOutcome) && (
        <Card aria-labelledby="lead-actions">
          <CardHeader><CardTitle id="lead-actions">Ações do lead</CardTitle></CardHeader>
          <CardContent className="space-y-4">
            {mutation.pending && <p role="status" className="text-sm text-slate-600">Salvando alteração…</p>}
            {allowMove && (
              <form method="post" onSubmit={submitMove} className="flex flex-wrap items-end gap-2">
                <label className="min-w-44 flex-1 text-sm font-semibold">Etapa de destino
                  <select name="stageId" className={fieldClass} defaultValue="" required disabled={disabled}>
                    <option value="">Selecione a etapa</option>
                    {destinations.map((destination) => <option key={destination.id} value={destination.id}>{destination.name}</option>)}
                  </select>
                </label>
                <Button type="submit" disabled={disabled} className="min-h-11">Mover para etapa</Button>
              </form>
            )}
            {allowOutcome && (recordLoss ? (
              <form method="post" onSubmit={submitLoss} className="space-y-3 rounded-lg border border-slate-200 p-3">
                <p className="text-sm text-slate-700">Confirme o motivo para encerrar esta oportunidade como perdida.</p>
                <label className="block text-sm font-semibold">Motivo da perda
                  <select name="lossReasonId" className={fieldClass} defaultValue="" required disabled={disabled}>
                    <option value="">Selecione o motivo</option>
                    {reasons.filter((reason) => !reason.archived).map((reason) => <option key={reason.id} value={reason.id}>{reason.name}</option>)}
                  </select>
                </label>
                <div className="flex flex-wrap gap-2">
                  <Button type="button" variant="outline" className="min-h-11" disabled={mutation.pending} onClick={() => setRecordLoss(false)}>Cancelar</Button>
                  <Button type="submit" variant="destructive" className="min-h-11" disabled={disabled}>Confirmar perda</Button>
                </div>
              </form>
            ) : <Button type="button" variant="outline" className="min-h-11" disabled={disabled || reasons.length === 0} onClick={() => setRecordLoss(true)}>Registrar perda</Button>)}
          </CardContent>
        </Card>
      )}
      {allowOutcome && registeringSale && (
        <RegisterSaleDialog lead={lead} mutation={mutation}
          onClose={() => { setRegisteringSale(false); mutation.clearFailure(); }}
          onReload={() => { setRegisteringSale(false); mutation.clearFailure(); reload(); }}
          onRegistered={() => {
            setRegisteringSale(false);
            setNotice("Venda registrada. O lead foi movido para Venda fechada.");
            reload();
          }} />
      )}
    </>
  );
}

export function LeadDetailPage({ leadId }: { leadId: string }) {
  return (
    <>
      <Link href="/app/funil" className="inline-flex min-h-11 items-center gap-2 rounded-sm text-sm font-semibold text-[#143e75] underline-offset-4 hover:underline focus-visible:outline-2">
        <ArrowLeft size={16} aria-hidden="true" />Voltar ao funil
      </Link>
      {isContactId(leadId) ? <LeadDetail key={leadId} leadId={leadId} /> : <PanelState kind="empty" title="Lead não encontrado" />}
    </>
  );
}
