"use client";

import { useCallback, useState } from "react";
import { Pencil } from "lucide-react";
import { useClinic } from "@/components/panel/clinic-context";
import { useClinicResource } from "@/components/panel/clinic-resource";
import { useTabVisible } from "@/components/panel/use-tab-visible";
import { PanelState } from "@/components/panel/panel-state";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { getLead } from "@/lib/api/leads";
import { OriginDetails } from "./origin-details";
import { OriginDialog, useOriginMutation } from "./origin-dialog";

type Props = { leadId: string; disabled: boolean; refreshed: (saved: boolean) => void };

export function LeadOriginCard(props: Props) {
  const mutation = useOriginMutation();
  const visible = useTabVisible();
  return visible ? <OriginContent {...props} mutation={mutation} /> : null;
}

function OriginContent({ leadId, disabled, refreshed, mutation }: Props & { mutation: ReturnType<typeof useOriginMutation> }) {
  const { clinic } = useClinic();
  const [editing, setEditing] = useState(false);
  const load = useCallback((signal: AbortSignal) => getLead(leadId, signal), [leadId]);
  const { result, reload } = useClinicResource(load);
  const lead = result?.data;
  const canEdit = clinic.permissions.LEAD_ORIGIN_EDIT === "ALL";

  function refresh(saved: boolean) {
    setEditing(false);
    mutation.clearFailure();
    refreshed(saved);
  }

  return (
    <Card aria-labelledby="lead-origin">
      <CardHeader className="flex flex-wrap items-center justify-between gap-3 space-y-0">
        <CardTitle id="lead-origin">Origem do lead</CardTitle>
        {canEdit && lead?.origin && <Button type="button" variant="outline" className="min-h-11"
          disabled={disabled || mutation.pending || Boolean(mutation.failure?.needsReload)}
          onClick={() => { mutation.clearFailure(); setEditing(true); }}><Pencil aria-hidden="true" />Corrigir origem</Button>}
      </CardHeader>
      <CardContent className="space-y-3">
        {!result && <p role="status" className="text-sm text-muted-foreground">Carregando origem…</p>}
        {result?.error && <PanelState kind={result.error.status === 403 ? "forbidden" : "error"}
          title="Não foi possível carregar a origem"
          message={result.error.status === 404 ? "O lead está indisponível nesta clínica." : undefined}
          onRetry={result.error.status === 403 || result.error.status === 404 ? undefined : reload} />}
        {lead && (lead.origin ? <OriginDetails origin={lead.origin} />
          : <p className="text-sm text-muted-foreground">A origem está indisponível para este lead.</p>)}
        {!editing && mutation.pending && <p role="status" className="text-sm text-muted-foreground">Salvando origem…</p>}
        {!editing && mutation.failure && <PanelState kind="conflict" title="A alteração não foi confirmada"
          message={mutation.failure.message} onRetry={mutation.failure.needsReload ? () => refresh(false) : undefined} />}
      </CardContent>
      {canEdit && editing && lead && <OriginDialog action={{ kind: "edit", lead }} mutation={mutation}
        close={() => { mutation.clearFailure(); setEditing(false); }} reload={() => refresh(false)} saved={() => refresh(true)} />}
    </Card>
  );
}
