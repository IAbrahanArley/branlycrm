"use client";

import { useCallback, useState } from "react";
import Link from "next/link";
import { ArrowLeft } from "lucide-react";
import { FunnelHeading, useFunnelResource, useLeadMutation } from "@/components/funnel/funnel-ui";
import { useClinic } from "@/components/panel/clinic-context";
import { PanelState } from "@/components/panel/panel-state";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { contactPath, getContact, isContactId } from "@/lib/api/contacts";
import { getSale, getSaleCatalog, getSaleHistory, paymentLabels, type SaleEvent } from "@/lib/api/sale-management";
import { formatSaleAmount } from "@/lib/sale-amount";
import { SaleChangeDialog } from "./sale-change-dialog";
import { SaleBadge, formatSaleDay } from "./sales-workspace";

const dateTime = new Intl.DateTimeFormat("pt-BR", { dateStyle: "short", timeStyle: "short", timeZone: "UTC" });
const historyLabel = { CREATED: "Venda registrada", CORRECTED: "Venda corrigida", CANCELLED: "Venda cancelada" };
type Catalog = Awaited<ReturnType<typeof getSaleCatalog>>;
const emptyCatalog: Catalog = { procedures: [], professionals: [] };
const linkStyle = "inline-flex min-h-11 items-center gap-2 rounded-sm text-sm font-semibold text-[#174578] hover:underline focus-visible:outline-2";

function catalogName(options: Catalog["procedures"], id: string | null) {
  if (id === null) return "Não informado";
  return options.find((option) => option.id === id)?.name ?? "Nome indisponível";
}

function change<T>(label: string, before: T, after: T, format: (value: T) => string) {
  return { label, changed: before !== after, before: format(before), after: format(after) };
}

function HistoryEntry({ event, catalog, subjectId }: { event: SaleEvent; catalog: Catalog; subjectId: string }) {
  const changes = [
    change("Valor", event.previousAmountCents, event.amountCents, (value) => value === null ? "Não informado" : formatSaleAmount(value)),
    change("Data da venda", event.previousSoldOn, event.soldOn, (value) => value === null ? "Não informada" : formatSaleDay(value)),
    change("Procedimento", event.previousProcedureId, event.procedureId, (value) => catalogName(catalog.procedures, value)),
    change("Profissional", event.previousProfessionalId, event.professionalId, (value) => catalogName(catalog.professionals, value)),
    change("Forma de pagamento", event.previousPaymentMethod, event.paymentMethod, (value) => value ? paymentLabels[value] : "Não informada"),
  ].filter((field) => field.changed);
  return (
    <li className="space-y-2 py-4">
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <h3 className="font-semibold">{historyLabel[event.type]}</h3>
        <time dateTime={event.occurredAt} className="text-sm text-slate-600 tabular-nums">{dateTime.format(new Date(event.occurredAt))} UTC</time>
      </div>
      <p className="text-xs text-slate-600 wrap-anywhere">Autor · {event.actorId === subjectId ? "Você" : event.actorId}</p>
      {event.reason && <p className="text-sm wrap-anywhere whitespace-pre-wrap">{event.reason}</p>}
      {event.type === "CORRECTED" && changes.length > 0 && (
        <dl className="grid gap-2 rounded-lg bg-slate-50 p-3 text-sm sm:grid-cols-2">
          {changes.map(({ label, before, after }) => (
            <div key={label} className="min-w-0">
              <dt className="font-semibold">{label}</dt>
              <dd className="text-slate-700 wrap-anywhere">Antes {before} · Depois {after}</dd>
            </div>
          ))}
        </dl>
      )}
    </li>
  );
}

function SaleDetail({ saleId }: { saleId: string }) {
  const { clinic, subjectId } = useClinic();
  const [action, setAction] = useState<"correct" | "cancel">();
  const [notice, setNotice] = useState("");
  const canReadContact = clinic.permissions.CONTACT_READ === "ALL";
  const load = useCallback(async (signal: AbortSignal) => {
    const sale = await getSale(saleId, signal);
    const contact = canReadContact ? await getContact(sale.contactId, signal).then(({ id, name }) => ({ id, name })) : null;
    return { sale, contact };
  }, [canReadContact, saleId]);
  const loadEvents = useCallback((signal: AbortSignal) => getSaleHistory(saleId, signal), [saleId]);
  const { result, reload } = useFunnelResource(load);
  const history = useFunnelResource(loadEvents);
  const catalog = useFunnelResource(getSaleCatalog);
  const mutation = useLeadMutation("Não foi possível confirmar a alteração. Recarregue a venda e confira a situação antes de repetir.");
  const data = result?.data;

  function refresh() {
    setAction(undefined);
    mutation.clearFailure();
    reload();
    history.reload();
    catalog.reload();
  }

  if (!data) {
    return <>
      <FunnelHeading title="Detalhe da venda" />
      {result?.error ? <PanelState kind={result.error.status === 403 ? "forbidden" : "error"}
        title={result.error.status === 404 ? "Venda não encontrada" : "Não foi possível carregar a venda"}
        message={result.error.message} onRetry={result.error.status === 404 ? undefined : refresh} />
        : <PanelState kind="loading" title="Carregando venda…" />}
    </>;
  }

  const { sale, contact } = data;
  const options = catalog.result?.data ?? emptyCatalog;
  const canCorrect = sale.status === "ACTIVE" && clinic.permissions.SALE_CORRECT === "ALL";
  const facts = [
    ["Procedimento", catalogName(options.procedures, sale.procedureId)],
    ["Profissional", catalogName(options.professionals, sale.professionalId)],
    ["Data da venda", formatSaleDay(sale.soldOn)],
    ["Forma de pagamento", sale.paymentMethod ? paymentLabels[sale.paymentMethod] : "Não informada"],
  ];

  return (
    <>
      <FunnelHeading title="Detalhe da venda" description="Confira o valor registrado e as alterações feitas pela equipe.">
        {canCorrect && (
          <div className="flex flex-wrap gap-2">
            <Button type="button" className="min-h-11" disabled={!catalog.result?.data}
              onClick={() => { setNotice(""); setAction("correct"); }}>Corrigir venda</Button>
            <Button type="button" variant="outline" className="min-h-11" onClick={() => { setNotice(""); setAction("cancel"); }}>Cancelar venda</Button>
          </div>
        )}
      </FunnelHeading>
      {notice && <p role="status" className="rounded-lg border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-950">{notice}</p>}
      {catalog.result?.error && <PanelState kind="error" title="Catálogo indisponível" message="Recarregue o catálogo para consultar os nomes e corrigir a venda." onRetry={catalog.reload} />}
      <Card aria-labelledby="sale-details">
        <CardHeader><CardTitle id="sale-details">Resumo da venda</CardTitle></CardHeader>
        <CardContent className="space-y-4">
          <div className="flex flex-wrap items-center gap-3">
            <p className="text-2xl font-bold tabular-nums">{formatSaleAmount(sale.amountCents)}</p>
            <SaleBadge status={sale.status} />
          </div>
          <dl className="grid gap-4 text-sm sm:grid-cols-2">
            {facts.map(([label, value]) => <div key={label}><dt className="text-slate-600">{label}</dt><dd className="font-semibold wrap-anywhere">{value}</dd></div>)}
            {sale.cancelReason && <div className="sm:col-span-2"><dt className="text-slate-600">Motivo do cancelamento</dt><dd className="wrap-anywhere whitespace-pre-wrap">{sale.cancelReason}</dd></div>}
          </dl>
          <div className="flex flex-wrap gap-x-5 gap-y-1">
            {contact && <Link className={linkStyle} href={contactPath(contact.id)}>Contato · {contact.name}</Link>}
            <Link className={linkStyle} href={`/app/funil/${sale.leadId}`}>Abrir lead</Link>
          </div>
        </CardContent>
      </Card>
      <Card aria-labelledby="sale-history">
        <CardHeader><CardTitle id="sale-history">Histórico da venda</CardTitle></CardHeader>
        <CardContent>
          {!history.result && <PanelState kind="loading" title="Carregando histórico…" />}
          {history.result?.error && <PanelState kind="error" title="Não foi possível carregar o histórico" message={history.result.error.message} onRetry={history.reload} />}
          {history.result?.data?.length === 0 && <p role="status" className="text-sm text-slate-600">Nenhum evento registrado.</p>}
          {history.result?.data && history.result.data.length > 0 && <ol className="divide-y divide-border">
            {history.result.data.map((event) => <HistoryEntry key={event.id} event={event} catalog={options} subjectId={subjectId} />)}
          </ol>}
        </CardContent>
      </Card>
      {action && canCorrect && <SaleChangeDialog sale={sale} kind={action} catalog={options} mutation={mutation} onReload={refresh}
        onClose={() => { setAction(undefined); mutation.clearFailure(); }}
        onSaved={() => { setNotice(action === "correct" ? "Venda corrigida." : "Venda cancelada. O lead permanece em Venda fechada."); refresh(); }} />}
    </>
  );
}

export function SaleDetailPage({ saleId }: { saleId: string }) {
  return <>
    <Link className={linkStyle} href="/app/vendas"><ArrowLeft size={16} aria-hidden="true" />Voltar às vendas</Link>
    {isContactId(saleId) ? <SaleDetail key={saleId} saleId={saleId} /> : <PanelState kind="empty" title="Venda não encontrada" />}
  </>;
}
