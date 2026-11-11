"use client";

import { useCallback, useEffect, useState } from "react";
import { useClinic } from "@/components/panel/clinic-context";
import { withSelectedClinic } from "@/lib/api/auth";
import { ApiError } from "@/lib/api/client";

export function clinicApiError(cause: unknown) {
  return cause instanceof ApiError ? cause : new ApiError(0, "UNKNOWN", "Não foi possível concluir a operação.");
}

export function clinicContextChanged(error: ApiError) {
  return ["CLINIC_NOT_SELECTED", "CLINIC_ACCESS_DENIED", "SESSION_CONTEXT_CHANGED"].includes(error.code);
}

export function useClinicResource<T>(load: (signal: AbortSignal) => Promise<T>) {
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
        const error = clinicApiError(cause);
        if (clinicContextChanged(error)) refresh();
        else setLoaded({ load, revision, error });
      },
    );
    return () => controller.abort();
  }, [clinic.tenantId, load, refresh, revision, subjectId]);
  return { result: loaded?.load === load && loaded.revision === revision ? loaded : undefined, reload };
}
