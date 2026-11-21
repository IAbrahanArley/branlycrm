"use client";

import { useCallback } from "react";
import Link from "next/link";
import { OriginDetails } from "@/components/funnel/origin-details";
import { useClinicResource } from "@/components/panel/clinic-resource";
import { useTabVisible } from "@/components/panel/use-tab-visible";
import { PanelState } from "@/components/panel/panel-state";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { listOriginTouches } from "@/lib/api/leads";

const date = new Intl.DateTimeFormat("pt-BR", { dateStyle: "short", timeStyle: "short", timeZone: "UTC" });

export function ContactOriginHistory({ contactId }: { contactId: string }) {
  const visible = useTabVisible();
  return visible ? <OriginHistory contactId={contactId} /> : null;
}

function OriginHistory({ contactId }: { contactId: string }) {
  const load = useCallback((signal: AbortSignal) => listOriginTouches(contactId, signal), [contactId]);
  const { result, reload } = useClinicResource(load);
  const error = result?.error;
  const touches = result?.data;
  const message = error?.code === "CLINIC_ACCESS_DENIED" || error?.code === "ACCESS_DENIED"
    ? "Sua conta não tem permissão para consultar estas chegadas."
    : "O histórico de chegadas está indisponível. Tente novamente.";
  return (
    <Card aria-labelledby="contact-origins-title">
      <CardHeader><CardTitle id="contact-origins-title">Histórico de chegadas</CardTitle></CardHeader>
      <CardContent className="space-y-3">
        {!result && <p role="status" className="text-sm text-muted-foreground">Carregando chegadas…</p>}
        {error && <PanelState kind={error.status === 403 ? "forbidden" : "error"}
          title="Não foi possível carregar as chegadas" message={message} onRetry={error.status === 403 ? undefined : reload} />}
        {touches && (touches.length ? (
          <ol className="divide-y rounded-lg border" aria-label="Chegadas mais recentes">
            {touches.map(({ id, leadId, receivedAt, origin }) => (
              <li className="space-y-2 p-4" key={id}>
                <p className="text-sm text-muted-foreground">
                  <time dateTime={receivedAt}>{date.format(new Date(receivedAt))} UTC</time>
                </p>
                <OriginDetails origin={origin} />
                {leadId && <Link href={`/app/funil/${leadId}`} className="inline-flex min-h-11 items-center rounded-sm text-sm font-semibold text-primary underline-offset-4 hover:underline focus-visible:outline-2">Abrir lead</Link>}
              </li>
            ))}
          </ol>
        ) : <p role="status" className="text-sm text-muted-foreground">Nenhuma chegada posterior com origem registrada.</p>)}
      </CardContent>
    </Card>
  );
}
