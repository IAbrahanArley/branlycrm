"use client";

import { useCallback, useState, type ReactNode } from "react";
import Link from "next/link";
import { Archive, ArchiveRestore, ArrowDownUp, ArrowLeft, ChevronDown, Pencil, Plus } from "lucide-react";
import { useClinic } from "@/components/panel/clinic-context";
import { useClinicResource } from "@/components/panel/clinic-resource";
import { PanelState } from "@/components/panel/panel-state";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { archiveLossReason, archiveStage, loadPipelineConfig, reorderStages, saveLossReason, saveStage } from "@/lib/api/pipeline-config";
import type { LossReason, Stage } from "@/lib/api/leads";
import { FunnelHeading } from "./funnel-ui";
import { PipelineConfigDialog, type ConfigDialogAction, type ConfigUpdate } from "./pipeline-config-dialog";

type Configuration = Awaited<ReturnType<typeof loadPipelineConfig>>;
const reasonOrder = new Intl.Collator("pt-BR", { sensitivity: "base" });

function ConfigRow({ name, detail, onEdit, onArchive, archived = false }: {
  name: string; detail?: string; onEdit?: () => void; onArchive?: () => void; archived?: boolean;
}) {
  const archiveLabel = `${archived ? "Desarquivar" : "Arquivar"} ${name}`;
  const ArchiveIcon = archived ? ArchiveRestore : Archive;
  return (
    <li className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 px-3 py-2 sm:px-4">
      <div className="min-w-0 flex-1 basis-40">
        <p className="font-semibold wrap-anywhere">{name}</p>
        {detail && <p className="text-xs text-muted-foreground">{detail}</p>}
      </div>
      <div className="flex shrink-0 items-center gap-1">
        {onEdit && <Button type="button" size="icon" variant="ghost" className="size-11 touch-manipulation"
          aria-label={`Renomear ${name}`} title={`Renomear ${name}`} onClick={onEdit}><Pencil aria-hidden="true" /></Button>}
        {onArchive && <Button type="button" size="icon" variant="ghost" className="size-11 touch-manipulation"
          aria-label={archiveLabel} title={archiveLabel} onClick={onArchive}><ArchiveIcon aria-hidden="true" /></Button>}
      </div>
    </li>
  );
}

function ArchivedItems({ label, count, children }: { label: string; count: number; children: ReactNode }) {
  return (
    <details className="group rounded-lg border">
      <summary className="flex min-h-11 cursor-pointer list-none items-center justify-between gap-2 rounded-lg px-3 py-2 text-sm font-semibold focus-visible:outline-2 focus-visible:outline-primary [&::-webkit-details-marker]:hidden sm:px-4">
        <span>{label} <span className="font-normal text-muted-foreground">({count})</span></span>
        <ChevronDown className="size-4 group-open:rotate-180" aria-hidden="true" />
      </summary>
      {count ? <ul className="divide-y border-t">{children}</ul> : <p className="border-t px-4 py-3 text-sm text-muted-foreground">Nenhum item arquivado.</p>}
    </details>
  );
}

function PipelineConfig() {
  const { result, reload } = useClinicResource(loadPipelineConfig);
  const [confirmed, setConfirmed] = useState<Configuration>();
  const [action, setAction] = useState<ConfigDialogAction>();
  const [notice, setNotice] = useState("");
  const data = confirmed ?? result?.data;
  const error = result?.error;

  function open(next: ConfigDialogAction) {
    setNotice("");
    setAction(next);
  }

  const refresh = useCallback((message?: string) => {
    if (message) setNotice(message);
    setAction(undefined);
    setConfirmed(undefined);
    reload();
  }, [reload]);

  function applyUpdate(update: ConfigUpdate, message: string) {
    if (!data) return;
    if ("stages" in update) {
      setConfirmed({ ...data, pipeline: update });
    } else {
      const reasons = [...data.reasons.filter(({ id }) => id !== update.id), update];
      reasons.sort((a, b) => reasonOrder.compare(a.name, b.name));
      setConfirmed({ ...data, reasons });
    }
    setAction(undefined);
    setNotice(message);
  }

  function renderConfig({ pipeline, reasons }: NonNullable<typeof data>) {
    const openStages = pipeline.stages.filter(({ kind }) => kind === "OPEN");
    const activeReasons = reasons.filter(({ archived }) => !archived);
    const archivedReasons = reasons.filter(({ archived }) => archived);

    function nameStage(stage?: Stage) {
      open({
        kind: "name", name: stage?.name, maxLength: 40, title: stage ? "Renomear etapa" : "Nova etapa",
        description: stage ? "O nome será atualizado no funil da clínica." : "A nova etapa entra no fim das etapas abertas.",
        save: (name) => saveStage(pipeline, name, stage?.id),
        success: stage ? "Etapa renomeada." : "Etapa criada.",
      });
    }

    function toggleStage(stage: Stage, archived: boolean) {
      open({
        kind: "archive", archived, title: archived ? "Arquivar etapa" : "Desarquivar etapa",
        description: archived ? `“${stage.name}” sairá do funil. Mova os leads em aberto antes de arquivá-la.`
          : `“${stage.name}” voltará ao fim das etapas abertas.`,
        save: () => archiveStage(pipeline, stage.id, archived),
        success: archived ? "Etapa arquivada." : "Etapa desarquivada.",
      });
    }

    function nameReason(reason?: LossReason) {
      open({
        kind: "name", name: reason?.name, maxLength: 60, title: reason ? "Renomear motivo de perda" : "Novo motivo de perda",
        description: reason ? "O nome será atualizado para toda a clínica." : "O motivo ficará disponível ao registrar uma perda.",
        save: (name) => saveLossReason(name, reason),
        success: reason ? "Motivo renomeado." : "Motivo criado.",
      });
    }

    function toggleReason(reason: LossReason) {
      const archived = !reason.archived;
      open({
        kind: "archive", archived, title: archived ? "Arquivar motivo de perda" : "Desarquivar motivo de perda",
        description: archived ? `“${reason.name}” sairá das opções para novas perdas. O histórico será preservado.`
          : `“${reason.name}” voltará às opções para novas perdas.`,
        save: () => archiveLossReason(reason, archived),
        success: archived ? "Motivo arquivado." : "Motivo desarquivado.",
      });
    }

    return (
      <div className="grid items-start gap-5 xl:grid-cols-2">
        <Card aria-labelledby="config-stages">
          <CardHeader className="space-y-3">
            <div>
              <CardTitle id="config-stages">Etapas do funil</CardTitle>
              <p className="mt-1 text-sm text-muted-foreground">A etapa de entrada recebe os novos leads. As etapas de encerramento ficam no fim.</p>
            </div>
            <div className="flex flex-wrap gap-2">
              <Button type="button" className="min-h-11" onClick={() => nameStage()}><Plus aria-hidden="true" />Nova etapa</Button>
              <Button type="button" variant="outline" className="min-h-11" disabled={openStages.length < 2} onClick={() => open({
                kind: "order", stages: openStages, title: "Reordenar etapas",
                description: "Arraste pela alça ou use as setas para mover as etapas abertas. Depois, salve a ordem.",
                save: (ids) => reorderStages(pipeline, ids), success: "Ordem das etapas salva.",
              })}><ArrowDownUp aria-hidden="true" />Reordenar</Button>
            </div>
          </CardHeader>
          <CardContent className="space-y-4">
            <ul className="divide-y rounded-lg border" aria-label="Etapas ativas">
              {pipeline.stages.map((stage) => {
                const detail = stage.kind === "WON" ? "Encerramento · Ganho" : stage.kind === "LOST" ? "Encerramento · Perdido"
                  : stage.key === "NEW" ? "Entrada do lead" : `Etapa aberta · Posição ${stage.position}`;
                return <ConfigRow key={stage.id} name={stage.name} detail={detail} onEdit={() => nameStage(stage)}
                  onArchive={stage.kind === "OPEN" && stage.key !== "NEW" ? () => toggleStage(stage, true) : undefined} />;
              })}
            </ul>
            <ArchivedItems label="Etapas arquivadas" count={pipeline.archivedStages.length}>
              {pipeline.archivedStages.map((stage) => <ConfigRow key={stage.id} name={stage.name} archived onArchive={() => toggleStage(stage, false)} />)}
            </ArchivedItems>
          </CardContent>
        </Card>
        <Card aria-labelledby="config-reasons">
          <CardHeader className="space-y-3">
            <div>
              <CardTitle id="config-reasons">Motivos de perda</CardTitle>
              <p className="mt-1 text-sm text-muted-foreground">Opções apresentadas à equipe ao encerrar um lead como perdido.</p>
            </div>
            <div><Button type="button" className="min-h-11" onClick={() => nameReason()}><Plus aria-hidden="true" />Novo motivo</Button></div>
          </CardHeader>
          <CardContent className="space-y-4">
            {activeReasons.length ? (
              <ul className="divide-y rounded-lg border" aria-label="Motivos ativos">
                {activeReasons.map((reason) => <ConfigRow key={reason.id} name={reason.name} onEdit={() => nameReason(reason)} onArchive={() => toggleReason(reason)} />)}
              </ul>
            ) : <p className="text-sm text-muted-foreground">Nenhum motivo ativo.</p>}
            <ArchivedItems label="Motivos arquivados" count={archivedReasons.length}>
              {archivedReasons.map((reason) => <ConfigRow key={reason.id} name={reason.name} archived onEdit={() => nameReason(reason)} onArchive={() => toggleReason(reason)} />)}
            </ArchivedItems>
          </CardContent>
        </Card>
      </div>
    );
  }

  return (
    <>
      <p role="status" className={notice ? "rounded-lg border bg-card px-4 py-3 text-sm" : "sr-only"}>{notice}</p>
      {!result && <PanelState kind="loading" title="Carregando configuração…" />}
      {error && <PanelState kind={error.status === 403 ? "forbidden" : "error"} title="Não foi possível carregar a configuração"
        message={error.status === 403 ? "Você não tem permissão para acessar a configuração do funil." : error.message}
        onRetry={error.status === 403 ? undefined : reload} />}
      {data && renderConfig(data)}
      {action && <PipelineConfigDialog action={action} onClose={() => setAction(undefined)} onReload={refresh} onSaved={applyUpdate} />}
    </>
  );
}

export function PipelineConfigPage() {
  const { clinic, subjectId } = useClinic();
  return (
    <>
      <Link href="/app/funil" className={buttonVariants({ variant: "ghost", className: "min-h-11 self-start" })}>
        <ArrowLeft aria-hidden="true" />Voltar ao funil
      </Link>
      <FunnelHeading title="Configuração do funil" description="Organize as etapas do atendimento e os motivos de perda desta clínica." />
      {clinic.permissions.COMMERCIAL_SETTINGS_EDIT === "ALL"
        ? <PipelineConfig key={`${clinic.tenantId}:${subjectId}`} />
        : <PanelState kind="forbidden" title="Acesso restrito" message="A configuração do funil está disponível para proprietários e gestores." />}
    </>
  );
}
