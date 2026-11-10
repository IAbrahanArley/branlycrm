"use client";

import { useRef, useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { selectClinic, type ClinicMembership } from "@/lib/api/auth";
import { ApiError, isMfaGate } from "@/lib/api/client";
import { clinicRoleLabel } from "@/lib/clinic-role";

export function ClinicPicker({
  clinics, currentId, onRefresh,
}: {
  clinics: ClinicMembership[];
  currentId?: string;
  onRefresh: () => void;
}) {
  const busy = useRef(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ApiError>();

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const id = new FormData(event.currentTarget).get("clinic");
    if (busy.current || typeof id !== "string" || !clinics.some((clinic) => clinic.tenantId === id)) return;
    busy.current = true;
    setPending(true);
    setError(undefined);
    try {
      await selectClinic(id);
      onRefresh();
    } catch (cause) {
      if (isMfaGate(cause) || (cause instanceof ApiError && cause.status === 401)) return;
      setError(cause instanceof ApiError ? cause : new ApiError(0, "UNKNOWN", "Não foi possível selecionar a clínica."));
      busy.current = false;
      setPending(false);
    }
  }

  return (
    <form method="post" onSubmit={submit} className="max-w-xl space-y-4">
      <fieldset disabled={pending} className="space-y-3">
        <legend className="mb-3 text-lg font-semibold">Selecione uma clínica</legend>
        {clinics.map((clinic) => (
          <label key={clinic.tenantId} className="flex min-h-16 cursor-pointer items-center gap-3 rounded-lg border border-border bg-card p-3 hover:bg-muted has-checked:border-primary has-focus-visible:ring-2 has-focus-visible:ring-primary">
            <input type="radio" name="clinic" value={clinic.tenantId} required defaultChecked={clinic.tenantId === currentId || clinics.length === 1} className="size-4 shrink-0 accent-current" />
            <span className="min-w-0 wrap-anywhere">
              <span className="block font-medium">{clinic.clinicName}</span>
              <span className="block text-sm text-muted-foreground">{clinicRoleLabel(clinic.role)}</span>
            </span>
          </label>
        ))}
      </fieldset>
      {error && <p role="alert" className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-900">{error.message}</p>}
      <div className="flex flex-wrap gap-2">
        {error?.code === "STATE_CONFLICT" ? (
          <Button type="button" onClick={onRefresh} className="min-h-11">Recarregar dados</Button>
        ) : (
          <Button type="submit" disabled={pending} className="min-h-11">{pending ? "Confirmando…" : "Acessar clínica"}</Button>
        )}
        {currentId && <Button type="button" variant="outline" disabled={pending} onClick={onRefresh} className="min-h-11">Voltar ao painel</Button>}
      </div>
      {pending && <p role="status" className="sr-only">Confirmando clínica na sessão.</p>}
    </form>
  );
}
