"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { useClinic } from "@/components/panel/clinic-context";
import { clinicApiError, clinicContextChanged } from "@/components/panel/clinic-resource";
import { PanelState } from "@/components/panel/panel-state";
import type { ApiError } from "@/lib/api/client";
import { withSelectedClinic } from "@/lib/api/auth";
import { funnelErrorMessage } from "@/lib/api/funnel-view";
import type { LeadStatus, Temperature } from "@/lib/api/leads";

export { useClinicResource as useFunnelResource } from "@/components/panel/clinic-resource";

export const fieldClass = "mt-1 min-h-11 w-full rounded-lg border border-slate-400 bg-white px-3 py-2 text-base text-slate-950 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary";
export const temperatureLabel: Record<Temperature, string> = { COLD: "Frio", WARM: "Morno", HOT: "Quente", QUALIFIED: "Qualificado" };
export const statusLabel: Record<LeadStatus, string> = { OPEN: "Em aberto", WON: "Ganho", LOST: "Perdido" };
const temperatureStyle: Record<Temperature, string> = {
  COLD: "bg-blue-50 text-blue-800", WARM: "bg-amber-50 text-amber-900", HOT: "bg-rose-50 text-rose-800", QUALIFIED: "bg-emerald-50 text-emerald-800",
};

export function TemperatureBadge({ temperature }: { temperature: Temperature | null }) {
  const style = temperature ? temperatureStyle[temperature] : "bg-slate-100 text-slate-800";
  return <span className={`inline-flex rounded-md px-2 py-1 text-xs font-semibold ${style}`}>{temperature ? temperatureLabel[temperature] : "Sem temperatura"}</span>;
}

export function FunnelHeading({ title, description, children }: { title: string; description?: string; children?: ReactNode }) {
  return (
    <header className="flex flex-wrap items-end justify-between gap-4">
      <div className="min-w-0 space-y-2">
        <h1 className="font-[family-name:var(--font-branly)] text-3xl font-extrabold tracking-tight wrap-anywhere sm:text-4xl">{title}</h1>
        {description && <p className="max-w-prose text-sm text-slate-600">{description}</p>}
      </div>
      {children}
    </header>
  );
}

export function FunnelProblem({ error, retry }: { error: ApiError; retry: () => void }) {
  return <PanelState kind={error.status === 403 ? "forbidden" : "error"} title={error.status === 404 ? "Lead não encontrado" : "Não foi possível carregar o funil"}
    message={funnelErrorMessage(error.code, error.message)} onRetry={error.status === 404 || error.status === 403 ? undefined : retry} />;
}

export function useLeadMutation(
  uncertainMessage = "Não foi possível confirmar a alteração. Atualize o funil e confira a situação antes de tentar novamente.",
  feedback?: (error: ApiError) => { message: string; needsReload: boolean },
) {
  const { clinic, subjectId, refresh } = useClinic();
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<{ code: string; message: string; needsReload: boolean; fields: readonly string[] }>();
  const busy = useRef(false);
  const alive = useRef(false);
  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; };
  }, []);

  async function run<T>(action: () => Promise<T>, onSuccess: (result: T) => void, preview?: () => void) {
    if (busy.current || failure?.needsReload) return;
    busy.current = true;
    setPending(true);
    setFailure(undefined);
    preview?.();
    try {
      const result = await withSelectedClinic(clinic.tenantId, action, undefined, subjectId);
      if (alive.current) onSuccess(result);
    } catch (cause) {
      if (!alive.current) return;
      const error = clinicApiError(cause);
      if (clinicContextChanged(error)) {
        refresh();
      } else {
        const uncertain = error.status === 0 || error.status >= 500 || (error.status >= 200 && error.status < 300);
        const problem = uncertain ? { message: uncertainMessage, needsReload: true } : feedback?.(error) ?? {
          message: funnelErrorMessage(error.code, error.message),
          needsReload: error.status === 409 || error.status === 404,
        };
        setFailure({ ...problem, code: error.code, fields: error.fields });
      }
    } finally {
      busy.current = false;
      if (alive.current) setPending(false);
    }
  }

  return { pending, failure, run, clearFailure: () => setFailure(undefined) };
}
