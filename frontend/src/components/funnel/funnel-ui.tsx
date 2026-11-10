"use client";

import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { useClinic } from "@/components/panel/clinic-context";
import { PanelState } from "@/components/panel/panel-state";
import { ApiError } from "@/lib/api/client";
import { withSelectedClinic } from "@/lib/api/auth";
import { funnelErrorMessage } from "@/lib/api/funnel-view";
import type { LeadStatus, Temperature } from "@/lib/api/leads";

export const fieldClass = "mt-1 min-h-11 w-full rounded-lg border border-slate-400 bg-white px-3 py-2 text-base text-slate-950 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary";
export const temperatureLabel: Record<Temperature, string> = { COLD: "Frio", WARM: "Morno", HOT: "Quente", QUALIFIED: "Qualificado" };
export const statusLabel: Record<LeadStatus, string> = { OPEN: "Em aberto", WON: "Ganho", LOST: "Perdido" };
const temperatureStyle: Record<Temperature, string> = {
  COLD: "bg-blue-50 text-blue-800", WARM: "bg-amber-50 text-amber-900", HOT: "bg-rose-50 text-rose-800", QUALIFIED: "bg-emerald-50 text-emerald-800",
};

export function TemperatureBadge({ temperature }: { temperature: Temperature }) {
  return <span className={`inline-flex rounded-md px-2 py-1 text-xs font-semibold ${temperatureStyle[temperature] ?? "bg-slate-100 text-slate-800"}`}>{temperatureLabel[temperature] ?? "Indefinida"}</span>;
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

function errorFor(cause: unknown) {
  return cause instanceof ApiError ? cause : new ApiError(0, "UNKNOWN", "Não foi possível concluir a operação.");
}

function contextChanged(error: ApiError) {
  return ["CLINIC_NOT_SELECTED", "CLINIC_ACCESS_DENIED", "SESSION_CONTEXT_CHANGED"].includes(error.code);
}

export function FunnelProblem({ error, retry }: { error: ApiError; retry: () => void }) {
  return <PanelState kind={error.status === 403 ? "forbidden" : "error"} title={error.status === 404 ? "Lead não encontrado" : "Não foi possível carregar o funil"}
    message={funnelErrorMessage(error.code, error.message)} onRetry={error.status === 404 || error.status === 403 ? undefined : retry} />;
}

export function useFunnelResource<T>(load: (signal: AbortSignal) => Promise<T>) {
  const { clinic, subjectId, refresh } = useClinic();
  const [revision, setRevision] = useState(0);
  const [loaded, setLoaded] = useState<{ load: typeof load; revision: number; data?: T; error?: ApiError }>();
  const reload = useCallback(() => setRevision((value) => value + 1), []);
  useEffect(() => {
    const controller = new AbortController();
    void withSelectedClinic(clinic.tenantId, () => load(controller.signal), controller.signal, subjectId).then(
      (data) => { if (!controller.signal.aborted) setLoaded({ load, revision, data }); },
      (cause: unknown) => {
        if (controller.signal.aborted) return;
        const error = errorFor(cause);
        if (contextChanged(error)) refresh();
        else setLoaded({ load, revision, error });
      },
    );
    return () => controller.abort();
  }, [clinic.tenantId, load, refresh, revision, subjectId]);
  return { result: loaded?.load === load && loaded.revision === revision ? loaded : undefined, reload };
}

export function useLeadMutation() {
  const { clinic, subjectId, refresh } = useClinic();
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<{ message: string; needsReload: boolean }>();
  const busy = useRef(false);
  const alive = useRef(false);
  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; };
  }, []);

  async function run(action: () => Promise<unknown>, onSuccess: () => void, preview?: () => void) {
    if (busy.current || failure?.needsReload) return;
    busy.current = true;
    setPending(true);
    setFailure(undefined);
    preview?.();
    try {
      await withSelectedClinic(clinic.tenantId, action, undefined, subjectId);
      if (alive.current) onSuccess();
    } catch (cause) {
      if (!alive.current) return;
      const error = errorFor(cause);
      if (contextChanged(error)) {
        refresh();
      } else {
        const uncertain = error.status === 0 || error.status >= 500 || (error.status >= 200 && error.status < 300);
        setFailure({
          message: uncertain ? "Não foi possível confirmar a alteração. Atualize o funil e confira a situação antes de tentar novamente." : funnelErrorMessage(error.code, error.message),
          needsReload: uncertain || error.status === 409 || error.status === 404,
        });
      }
    } finally {
      busy.current = false;
      if (alive.current) setPending(false);
    }
  }

  return { pending, failure, run, clearFailure: () => setFailure(undefined) };
}
