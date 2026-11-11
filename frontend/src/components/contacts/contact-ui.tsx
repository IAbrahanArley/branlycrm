"use client";

import { useCallback, useEffect, useState, type ReactNode } from "react";
import Link from "next/link";
import { buttonVariants } from "@/components/ui/button";
import { PanelState } from "@/components/panel/panel-state";
import { useClinic } from "@/components/panel/clinic-context";
import { ApiError } from "@/lib/api/client";
import { withContactClinic } from "@/lib/api/contacts";

export const contactInput = "block min-h-11 w-full rounded-lg border border-slate-400 bg-white px-3 py-2 text-base text-slate-950 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#01247d] aria-invalid:border-red-700 disabled:bg-slate-50";

export function ContactHeading({ title, description, action, back = false }: { title: string; description?: string; action?: ReactNode; back?: boolean }) {
  return (
    <header className="space-y-3">
      {back && <Link href="/app/contatos" className="inline-flex min-h-11 items-center rounded-sm text-sm font-semibold text-[#24518a] hover:underline focus-visible:outline-2 focus-visible:outline-offset-2">Voltar à lista de contatos</Link>}
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 space-y-2">
          <h1 className="font-[family-name:var(--font-branly)] text-3xl font-extrabold tracking-tight wrap-anywhere sm:text-4xl">{title}</h1>
          {description && <p className="text-sm text-slate-600">{description}</p>}
        </div>
        {action}
      </div>
    </header>
  );
}

export function ContactLink({ href, children, primary = false }: { href: string; children: ReactNode; primary?: boolean }) {
  return <Link href={href} className={buttonVariants({ variant: primary ? "default" : "outline", className: "min-h-11 gap-2 motion-reduce:transition-none" })}>{children}</Link>;
}

export function PatientBadge() {
  return <span className="inline-flex w-fit rounded-full bg-emerald-50 px-2.5 py-1 text-xs font-semibold text-emerald-800">Paciente</span>;
}

export function ContactProblem({ error, onRetry }: { error: ApiError; onRetry?: () => void }) {
  if (error.status === 404) return <PanelState kind="error" title="Contato não encontrado" message="Volte à lista e confira se ele está disponível nesta clínica." />;
  if (error.status === 403) return <PanelState kind="forbidden" title="Permissão negada" message={error.message} />;
  return <PanelState kind={error.code === "STATE_CONFLICT" ? "conflict" : "error"} title="Não foi possível carregar os dados" message={error.message} onRetry={onRetry} />;
}

export function asContactError(error: unknown) {
  return error instanceof ApiError ? error : new ApiError(0, "UNKNOWN", "Não foi possível concluir a operação. Tente novamente.");
}

export function clinicNeedsRefresh(error: ApiError) {
  return error.code === "CLINIC_NOT_SELECTED" || error.code === "CLINIC_ACCESS_DENIED";
}

export function mutationResultUncertain(error: ApiError) {
  return error.status === 0 || error.status >= 500 || (error.status >= 200 && error.status < 300);
}

type Result<T> = { data: T; error?: never } | { error: ApiError; data?: never };

export function useContactResource<T>(load: (signal: AbortSignal) => Promise<T>) {
  const { clinic, refresh } = useClinic();
  const tenantId = clinic.tenantId;
  const [revision, setRevision] = useState(0);
  const [loaded, setLoaded] = useState<{ load: typeof load; revision: number; result: Result<T> }>();
  const reload = useCallback(() => setRevision((value) => value + 1), []);

  useEffect(() => {
    const controller = new AbortController();
    void withContactClinic(tenantId, () => load(controller.signal), controller.signal).then(
      (data) => {
        if (!controller.signal.aborted) setLoaded({ load, revision, result: { data } });
      },
      (cause: unknown) => {
        if (controller.signal.aborted) return;
        const error = asContactError(cause);
        if (clinicNeedsRefresh(error)) refresh();
        else setLoaded({ load, revision, result: { error } });
      },
    );
    return () => controller.abort();
  }, [load, refresh, revision, tenantId]);

  const result = loaded?.load === load && loaded.revision === revision ? loaded.result : undefined;
  return { result, reload };
}
