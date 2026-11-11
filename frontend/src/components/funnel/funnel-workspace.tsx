"use client";

import { useCallback, useRef, useState, type FormEvent } from "react";
import Link from "next/link";
import { Dialog } from "@base-ui/react/dialog";
import { ArrowRightLeft, ChevronLeft, ChevronRight, Columns3, GripVertical, List, Settings2 } from "lucide-react";
import { useClinic } from "@/components/panel/clinic-context";
import { PanelState } from "@/components/panel/panel-state";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { getPipeline, listLeads, moveLead, temperatures, type LeadPage, type LeadStatus, type LeadSummary, type Stage, type Temperature } from "@/lib/api/leads";
import { previewStageMove } from "@/lib/api/funnel-view";
import { fieldClass, FunnelHeading, FunnelProblem, statusLabel, TemperatureBadge, temperatureLabel, useFunnelResource, useLeadMutation } from "./funnel-ui";

type Move = { lead: LeadSummary; stageId: string };
type Filters = { status: LeadStatus; stageId: string; temperature: Temperature | "" };
const initial: Filters = { status: "OPEN", stageId: "", temperature: "" };
const stageAccent: Record<LeadStatus, string> = { OPEN: "bg-sky-500", WON: "bg-emerald-600", LOST: "bg-rose-500" };
const leadLink = "inline-flex min-h-10 items-center rounded-sm font-bold text-[#143e75] wrap-anywhere hover:underline focus-visible:outline-2 focus-visible:outline-offset-2";

function Pages({ data, title, onPage }: { data: LeadPage; title: string; onPage: (page: number) => void }) {
  const last = Math.min(Math.max(1, Math.ceil(data.total / data.pageSize)), Math.floor(10_000 / data.pageSize) + 1);
  if (last === 1 && data.page === 1) return null;
  return (
    <nav aria-label={`Paginação de ${title}`} className="flex items-center justify-between gap-2">
      <Button type="button" variant="outline" size="icon" className="size-11" aria-label="Página anterior" disabled={data.page <= 1} onClick={() => onPage(Math.min(last, data.page - 1))}><ChevronLeft aria-hidden="true" /></Button>
      <span className="text-xs text-slate-700 tabular-nums">Página {data.page} de {last}</span>
      <Button type="button" variant="outline" size="icon" className="size-11" aria-label="Próxima página" disabled={data.page >= last} onClick={() => onPage(data.page + 1)}><ChevronRight aria-hidden="true" /></Button>
    </nav>
  );
}

function MoveDialog({ lead, stages, onClose, onMove }: { lead: LeadSummary; stages: Stage[]; onClose: () => void; onMove: (stageId: string) => void }) {
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const stageId = new FormData(event.currentTarget).get("stageId");
    if (typeof stageId === "string" && stages.some((stage) => stage.id === stageId)) onMove(stageId);
  }
  return (
    <Dialog.Root open onOpenChange={(open) => { if (!open) onClose(); }}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-slate-950/55" />
        <Dialog.Popup className="fixed top-1/2 left-1/2 z-50 max-h-[90dvh] w-[calc(100%-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2 space-y-4 overflow-y-auto rounded-xl border bg-card p-5 shadow-xl">
          <Dialog.Title className="text-lg font-bold">Mover para etapa</Dialog.Title>
          <Dialog.Description className="text-sm text-slate-700 wrap-anywhere">{lead.contactName}</Dialog.Description>
          <form method="post" onSubmit={submit} className="space-y-4">
            <label className="block text-sm font-semibold">Etapa de destino
              <select className={fieldClass} name="stageId" defaultValue="" required>
                <option value="">Selecione a etapa</option>
                {stages.map((stage) => <option key={stage.id} value={stage.id}>{stage.name}</option>)}
              </select>
            </label>
            <div className="flex flex-wrap justify-end gap-2">
              <Dialog.Close render={<Button type="button" variant="outline" className="min-h-11" />}>Cancelar</Dialog.Close>
              <Button type="submit" className="min-h-11">Mover lead</Button>
            </div>
          </form>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function LeadTile({ lead, canMove, stageName, onMove, onDrag, onDrop }: {
  lead: LeadSummary; canMove: boolean; stageName?: string; onMove: () => void;
  onDrag?: () => void; onDrop?: (stageId?: string) => void;
}) {
  const pointerStart = useRef<{ id: number; x: number; y: number } | null>(null);
  return (
    <li className="min-w-0 rounded-lg border border-slate-200 bg-white p-3 shadow-xs">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <Link href={`/app/funil/${lead.id}`} prefetch={false} className={leadLink}>{lead.contactName}</Link>
          <p className="text-sm text-slate-600 tabular-nums wrap-anywhere">{lead.contactPhone}</p>
          {stageName && <p className="mt-1 text-sm text-slate-600 wrap-anywhere">{stageName}</p>}
        </div>
        {canMove && onDrag && onDrop && (
          <span aria-hidden="true" className="-mt-1 -mr-1 grid size-11 shrink-0 cursor-grab touch-none place-items-center rounded-md text-slate-500 select-none hover:bg-slate-100 active:cursor-grabbing"
            onPointerDown={(event) => {
              if (!event.isPrimary || event.button !== 0) return;
              pointerStart.current = { id: event.pointerId, x: event.clientX, y: event.clientY };
              event.currentTarget.setPointerCapture(event.pointerId);
            }}
            onPointerMove={(event) => {
              const start = pointerStart.current;
              if (start?.id === event.pointerId && Math.hypot(event.clientX - start.x, event.clientY - start.y) >= 6) onDrag();
            }}
            onPointerUp={(event) => {
              const start = pointerStart.current;
              if (start?.id !== event.pointerId) return;
              pointerStart.current = null;
              const moved = Math.hypot(event.clientX - start.x, event.clientY - start.y) >= 6;
              const stageId = moved ? document.elementFromPoint(event.clientX, event.clientY)?.closest<HTMLElement>("[data-funnel-stage]")?.dataset.funnelStage : undefined;
              onDrop(stageId);
            }}
            onLostPointerCapture={() => {
              if (!pointerStart.current) return;
              pointerStart.current = null;
              onDrop();
            }}>
            <GripVertical className="size-4" />
          </span>
        )}
      </div>
      <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
        <TemperatureBadge temperature={lead.temperature} />
        {canMove && <Button type="button" variant="ghost" className="min-h-11 gap-1.5" aria-label={`Mover ${lead.contactName} para etapa`} onClick={onMove}>
          <ArrowRightLeft size={15} aria-hidden="true" />Mover
        </Button>}
      </div>
    </li>
  );
}

export function FunnelWorkspace() {
  const { clinic } = useClinic();
  const [view, setView] = useState<"board" | "list">("board");
  const [filters, setFilters] = useState(initial);
  const [listPage, setListPage] = useState(1);
  const [columnPages, setColumnPages] = useState<Record<string, number>>({});
  const [selectedLead, setSelectedLead] = useState<LeadSummary>();
  const [preview, setPreview] = useState<Move>();
  const [draggedLead, setDraggedLead] = useState<LeadSummary>();
  const [notice, setNotice] = useState("");
  const pipeline = useFunnelResource(getPipeline);
  const stages = pipeline.result?.data?.stages;
  const mutation = useLeadMutation();
  const writable = clinic.permissions.LEAD_WRITE === "ALL";
  const canAct = writable && !mutation.pending && !mutation.failure?.needsReload;

  const load = useCallback(async (signal: AbortSignal) => {
    if (!stages) return {};
    if (view === "list") {
      return { list: await listLeads({ ...filters, page: listPage }, signal) };
    }
    const columns = stages.filter((stage) => !filters.stageId || filters.stageId === stage.id);
    const pages = await Promise.all(columns.map(async (stage) => ({
      stage,
      data: await listLeads({ status: stage.kind, stageId: stage.id, temperature: filters.temperature, page: columnPages[stage.id] ?? 1 }, signal),
    })));
    return { pages };
  }, [columnPages, filters, listPage, stages, view]);
  const leads = useFunnelResource(load);
  const openStages = stages?.filter((stage) => stage.kind === "OPEN") ?? [];
  const liveMove = mutation.pending ? preview : undefined;

  function changeFilters(next: Filters) {
    setFilters(next);
    setListPage(1);
    setColumnPages({});
  }

  function refresh() {
    mutation.clearFailure();
    setNotice("");
    pipeline.reload();
    leads.reload();
  }

  function move(lead: LeadSummary, stageId: string) {
    const target = openStages.find((stage) => stage.id === stageId);
    if (!canAct || lead.status !== "OPEN" || !target || lead.stageId === stageId) return;
    setSelectedLead(undefined);
    setNotice("");
    void mutation.run(() => moveLead(lead, stageId), () => {
      setNotice(`Lead movido para ${target.name}.`);
      leads.reload();
    }, () => setPreview({ lead, stageId }));
  }

  const data = leads.result?.data;
  const stageOptions = stages?.filter((stage) => view === "board" || stage.kind === filters.status) ?? [];
  return (
    <>
      <FunnelHeading title="Funil de vendas" description="Acompanhe os leads da clínica e mova cada oportunidade conforme o atendimento avança.">
        <div className="flex flex-wrap items-center gap-2">
          {clinic.permissions.COMMERCIAL_SETTINGS_EDIT === "ALL" && (
            <Link href="/app/funil/configuracao" className={buttonVariants({ variant: "outline", className: "min-h-11 gap-2" })}>
              <Settings2 size={16} aria-hidden="true" />Configurar funil
            </Link>
          )}
          <div role="group" aria-label="Visualização do funil" className="inline-flex rounded-lg border border-slate-200 bg-white p-1">
            <Button type="button" variant={view === "board" ? "secondary" : "ghost"} className="min-h-11 gap-2" aria-pressed={view === "board"} disabled={mutation.pending}
              onClick={() => { setView("board"); changeFilters({ ...filters, stageId: "" }); }}><Columns3 size={16} aria-hidden="true" />Kanban</Button>
            <Button type="button" variant={view === "list" ? "secondary" : "ghost"} className="min-h-11 gap-2" aria-pressed={view === "list"} disabled={mutation.pending}
              onClick={() => { setView("list"); changeFilters({ ...filters, stageId: "" }); }}><List size={16} aria-hidden="true" />Lista</Button>
          </div>
        </div>
      </FunnelHeading>
      {pipeline.result?.error && <FunnelProblem error={pipeline.result.error} retry={refresh} />}
      {!pipeline.result && <PanelState kind="loading" title="Carregando etapas…" />}
      {stages && (
        <>
          <Card aria-label="Filtros do funil" className="grid gap-3 p-4 sm:grid-cols-3">
            {view === "list" && <label className="text-sm font-semibold">Situação
              <select className={fieldClass} name="status" value={filters.status} disabled={mutation.pending}
                onChange={(event) => changeFilters({ ...filters, status: event.target.value as LeadStatus, stageId: "" })}>
                {Object.entries(statusLabel).map(([key, label]) => <option key={key} value={key}>{label}</option>)}
              </select>
            </label>}
            <label className="text-sm font-semibold">Etapa
              <select className={fieldClass} name="stage" value={filters.stageId} disabled={mutation.pending}
                onChange={(event) => changeFilters({ ...filters, stageId: event.target.value })}>
                <option value="">Todas as etapas</option>
                {stageOptions.map((stage) => <option key={stage.id} value={stage.id}>{stage.name}</option>)}
              </select>
            </label>
            <label className="text-sm font-semibold">Temperatura
              <select className={fieldClass} name="temperature" value={filters.temperature} disabled={mutation.pending}
                onChange={(event) => changeFilters({ ...filters, temperature: event.target.value as Filters["temperature"] })}>
                <option value="">Todas as temperaturas</option>
                {temperatures.map((temperature) => <option key={temperature} value={temperature}>{temperatureLabel[temperature]}</option>)}
              </select>
            </label>
          </Card>
          {mutation.failure && <PanelState kind="conflict" title="O movimento não foi confirmado" message={mutation.failure.message} onRetry={refresh} />}
          <p role="status" className={notice || mutation.pending ? "rounded-lg border border-slate-200 bg-white px-4 py-3 text-sm" : "sr-only"}>
            {mutation.pending ? "Salvando movimento…" : notice}
          </p>
          {!leads.result && <PanelState kind="loading" title="Carregando leads…" />}
          {leads.result?.error && <FunnelProblem error={leads.result.error} retry={leads.reload} />}
          {data?.pages && (
            <div className="grid gap-3 md:flex md:items-start md:overflow-x-auto md:pb-3" aria-label="Quadro de leads" role="region" tabIndex={0}>
              {data.pages.map(({ stage, data }) => {
                const page = liveMove ? previewStageMove(data, liveMove.lead, liveMove.lead.stageId, liveMove.stageId, stage.id) : data;
                const dropAllowed = Boolean(draggedLead && canAct && stage.kind === "OPEN" && stage.id !== draggedLead.stageId);
                return (
                  <section key={stage.id} aria-labelledby={`stage-${stage.id}`} data-funnel-stage={stage.id}
                    className={`min-w-0 space-y-3 rounded-xl border p-3 md:w-72 md:shrink-0 ${dropAllowed ? "border-sky-400 bg-sky-50" : "border-slate-200 bg-slate-100/70"}`}>
                    <header className="flex items-center gap-2">
                      <span className={`size-2 shrink-0 rounded-full ${stageAccent[stage.kind]}`} aria-hidden="true" />
                      <h2 id={`stage-${stage.id}`} className="min-w-0 flex-1 text-sm font-bold wrap-anywhere">{stage.name}</h2>
                      <span className="rounded-md bg-white px-2 py-1 text-xs font-semibold text-slate-700 tabular-nums" aria-label={`${page.total} leads`}>{page.total}</span>
                    </header>
                    {page.items.length ? <ul className="space-y-2">
                      {page.items.map((lead) => <LeadTile key={lead.id} lead={lead} canMove={canAct && lead.status === "OPEN" && openStages.length > 1}
                        onMove={() => setSelectedLead(lead)} onDrag={() => setDraggedLead(lead)} onDrop={(stageId) => {
                          setDraggedLead(undefined);
                          if (stageId) move(lead, stageId);
                        }} />)}
                    </ul> : <p className="rounded-lg border border-dashed border-slate-300 px-3 py-4 text-sm text-slate-600">Nenhum lead nesta etapa.</p>}
                    <Pages data={page} title={stage.name} onPage={(number) => setColumnPages((value) => ({ ...value, [stage.id]: number }))} />
                  </section>
                );
              })}
            </div>
          )}
          {data?.list && (
            <Card aria-label="Lista de leads" className="space-y-4 p-4">
              {data.list.items.length ? <ul className="grid gap-3 sm:grid-cols-2">
                {data.list.items.filter((lead) => !liveMove || !filters.stageId || lead.id !== liveMove.lead.id).map((lead) => {
                  const displayed = liveMove?.lead.id === lead.id ? { ...lead, stageId: liveMove.stageId } : lead;
                  return <LeadTile key={lead.id} lead={displayed} stageName={stages.find((stage) => stage.id === displayed.stageId)?.name}
                    canMove={canAct && lead.status === "OPEN" && openStages.length > 1} onMove={() => setSelectedLead(lead)} />;
                })}
              </ul> : <p className="text-sm text-slate-600">Nenhum lead encontrado com estes filtros.</p>}
              <Pages data={data.list} title="leads" onPage={setListPage} />
            </Card>
          )}
        </>
      )}
      {selectedLead && <MoveDialog lead={selectedLead} stages={openStages.filter((stage) => stage.id !== selectedLead.stageId)}
        onClose={() => setSelectedLead(undefined)} onMove={(stageId) => move(selectedLead, stageId)} />}
    </>
  );
}
