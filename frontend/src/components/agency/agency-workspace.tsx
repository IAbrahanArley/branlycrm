"use client";

import { useEffect, useState } from "react";
import { Building2, Plus } from "lucide-react";
import { PanelState } from "@/components/panel/panel-state";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { listPortfolio, type AgencyMembership, type ClinicDraft, type PortfolioClinic, type PortfolioPage } from "@/lib/api/agencies";
import { agencyErrorMessage } from "@/lib/api/agency-feedback";
import { readClinicRequest, writeClinicRequest } from "@/lib/api/agency-request";
import { ApiError } from "@/lib/api/client";
import { AgencyActionDialog, agencyInput, confirmAgencyAccount, mustRefreshAgency, type AgencyAction } from "./agency-action";
import { useAgencies } from "./agency-context";

const clinicStatus: Record<PortfolioClinic["status"], string> = {
  PROVISIONING: "Em preparação", AWAITING_OWNER: "Aguardando gestor", ACTIVE: "Ativa", SUSPENDED: "Suspensa", FAILED: "Falha na preparação",
};
const invitationStatus: Record<PortfolioClinic["ownerInvitation"], string> = {
  PENDING: "Pendente", EXPIRED: "Vencido", ACCEPTED: "Aceito", NONE: "Sem convite",
};

function Portfolio({ agency, retryDraft, setRetryDraft }: {
  agency: AgencyMembership; retryDraft?: ClinicDraft; setRetryDraft: (draft?: ClinicDraft) => void;
}) {
  const { subjectId, refresh } = useAgencies();
  const [page, setPage] = useState(1);
  const [revision, setRevision] = useState(0);
  const [loaded, setLoaded] = useState<{ page: number; revision: number; data?: PortfolioPage; error?: string }>();
  const [action, setAction] = useState<AgencyAction>();
  const [notice, setNotice] = useState("");
  const [formProblem, setFormProblem] = useState("");
  const [blockedUntil, setBlockedUntil] = useState(0);
  const [savedRequestId, setSavedRequestId] = useState(() => {
    try {
      return readClinicRequest(subjectId, agency.agencyId, window.sessionStorage);
    } catch {
      return undefined;
    }
  });

  function rememberRequest(requestId?: string) {
    try {
      writeClinicRequest(subjectId, agency.agencyId, requestId, window.sessionStorage);
      setSavedRequestId(requestId);
      return true;
    } catch {
      return false;
    }
  }

  useEffect(() => {
    const controller = new AbortController();
    async function load() {
      await confirmAgencyAccount(subjectId, controller.signal);
      const data = await listPortfolio(agency.agencyId, page, controller.signal);
      await confirmAgencyAccount(subjectId, controller.signal);
      if (!controller.signal.aborted) setLoaded({ page, revision, data });
    }
    void load().catch((cause: unknown) => {
      if (controller.signal.aborted) return;
      if (mustRefreshAgency(cause)) {
        if (cause instanceof ApiError && cause.code === "SESSION_CONTEXT_CHANGED") refresh();
        else if (cause instanceof ApiError) setLoaded({
          page, revision, error: agencyErrorMessage(cause.code, "Esta carteira não está mais disponível para sua conta."),
        });
        return;
      }
      setLoaded({ page, revision, error: cause instanceof ApiError ? cause.message : "Tente carregar a carteira novamente." });
    });
    return () => controller.abort();
  }, [agency.agencyId, page, refresh, revision, subjectId]);

  useEffect(() => {
    if (!blockedUntil) return;
    let timer: number;
    function unlock() {
      const remaining = blockedUntil - Date.now();
      if (remaining <= 0) setBlockedUntil(0);
      else timer = window.setTimeout(unlock, Math.min(remaining, 2_147_483_647));
    }
    timer = window.setTimeout(unlock, Math.min(blockedUntil - Date.now(), 2_147_483_647));
    return () => window.clearTimeout(timer);
  }, [blockedUntil]);

  const result = loaded?.page === page && loaded.revision === revision ? loaded : undefined;
  const data = result?.data;
  const maxPage = data ? Math.min(Math.max(1, Math.ceil(data.total / data.pageSize)), Math.floor(10_000 / data.pageSize) + 1) : 1;

  function startCreate() {
    const requestId = savedRequestId ?? globalThis.crypto?.randomUUID?.();
    if (!requestId) {
      setFormProblem("Abra o painel por uma conexão segura para cadastrar uma clínica.");
      return;
    }
    setFormProblem("");
    setAction({ kind: "create", requestId });
  }

  function finish(message: string, refreshAccess?: boolean) {
    const wasCreating = action?.kind === "create";
    setAction(undefined);
    setNotice(message);
    if (refreshAccess) {
      refresh();
      return;
    }
    if (wasCreating && message) setPage(1);
    setRevision((value) => value + 1);
  }

  return (
    <>
      <Card aria-labelledby="portfolio-title">
        <CardHeader className="flex flex-wrap items-start justify-between gap-3 space-y-0">
          <div className="min-w-0 space-y-1">
            <CardTitle id="portfolio-title" className="text-lg wrap-anywhere">{agency.name}</CardTitle>
            <p className="text-sm text-slate-600">Clínicas da carteira e convites dos gestores.</p>
          </div>
          {data && <Button type="button" className="min-h-11 gap-2" onClick={startCreate}><Plus aria-hidden="true" />{savedRequestId ? "Retomar cadastro" : "Nova clínica"}</Button>}
        </CardHeader>
        <CardContent className="space-y-4">
          {formProblem && <p role="alert" className="text-sm text-red-800">{formProblem}</p>}
          {savedRequestId && !action && <p role="status" className="rounded-lg bg-amber-50 p-3 text-sm text-amber-950">Há um cadastro sem confirmação. Use Retomar cadastro para repetir a mesma solicitação.</p>}
          <p role="status" className={notice ? "rounded-lg border border-border bg-slate-50 px-4 py-3 text-sm wrap-anywhere" : "sr-only"}>{notice}</p>
          {!result && <PanelState kind="loading" title="Carregando a carteira…" />}
          {result?.error && <PanelState kind="error" title="Não foi possível carregar a carteira" message={result.error} onRetry={refresh} />}
          {data && data.items.length === 0 && <PanelState kind="empty" title={page === 1 ? "Nenhuma clínica na carteira" : "Nenhuma clínica nesta página"} message="As clínicas cadastradas aparecem aqui." />}
          {data && data.items.length > 0 && (
            <ul className="divide-y divide-border border-y border-border">
              {data.items.map((clinic) => {
                const eligible = (clinic.status === "PROVISIONING" || clinic.status === "AWAITING_OWNER") && clinic.ownerInvitation !== "ACCEPTED";
                return (
                  <li key={clinic.tenantId} className="flex flex-wrap items-center justify-between gap-3 py-4">
                    <div className="flex min-w-0 items-start gap-3">
                      <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-slate-100 text-[#24518a]"><Building2 size={18} aria-hidden="true" /></span>
                      <div className="min-w-0 space-y-1 wrap-anywhere">
                        <h3 className="font-semibold">{clinic.clinicName}</h3>
                        <p className="text-sm text-slate-700">Clínica · {clinicStatus[clinic.status] ?? "Situação indisponível"}</p>
                        <p className="text-sm text-slate-600">Convite do gestor · {invitationStatus[clinic.ownerInvitation] ?? "Situação indisponível"}</p>
                      </div>
                    </div>
                    {eligible && <Button type="button" variant="outline" className="min-h-11" aria-label={`Reemitir convite do gestor de ${clinic.clinicName}`} onClick={() => setAction({ kind: "reissue", clinic })}>Reemitir convite</Button>}
                  </li>
                );
              })}
            </ul>
          )}
          {data && (maxPage > 1 || page > 1) && (
            <nav aria-label="Paginação da carteira" className="flex flex-wrap items-center justify-between gap-2">
              <p role="status" className="text-sm text-slate-600">Página {page} de {maxPage} · {data.total.toLocaleString("pt-BR")} clínicas</p>
              <div className="flex gap-2">
                <Button type="button" variant="outline" className="min-h-11" disabled={page === 1} onClick={() => setPage(Math.min(maxPage, page - 1))}>Anterior</Button>
                <Button type="button" variant="outline" className="min-h-11" disabled={page >= maxPage} onClick={() => setPage(page + 1)}>Próxima</Button>
              </div>
            </nav>
          )}
        </CardContent>
      </Card>
      {action && <AgencyActionDialog agencyId={agency.agencyId} action={action}
        retryDraft={action.kind === "create" ? retryDraft : undefined} setRetryDraft={setRetryDraft}
        recovered={action.kind === "create" && savedRequestId === action.requestId} rememberRequest={rememberRequest}
        blockedUntil={blockedUntil} onLimit={setBlockedUntil} onClose={() => setAction(undefined)} onDone={finish} />}
    </>
  );
}

export function AgencyWorkspace() {
  const { agencies } = useAgencies();
  const [selected, setSelected] = useState("");
  const [drafts, setDrafts] = useState<Record<string, ClinicDraft | undefined>>({});
  const agency = agencies.find((item) => item.agencyId === selected) ?? agencies[0];

  return (
    <>
      <header className="space-y-2">
        <h1 className="font-[family-name:var(--font-branly)] text-3xl font-extrabold tracking-tight sm:text-4xl">Carteira da agência</h1>
        <p className="text-sm text-slate-600">Acompanhe a preparação das clínicas e a entrada de seus gestores.</p>
      </header>
      {agencies.length > 1 && (
        <label className="block max-w-sm space-y-1 text-sm font-medium">Agência
          <select name="agency" className={agencyInput} value={agency.agencyId} onChange={(event) => setSelected(event.target.value)}>
            {agencies.map((item) => <option key={item.agencyId} value={item.agencyId}>{item.name}</option>)}
          </select>
        </label>
      )}
      <Portfolio key={agency.agencyId} agency={agency} retryDraft={drafts[agency.agencyId]}
        setRetryDraft={(draft) => setDrafts((previous) => ({ ...previous, [agency.agencyId]: draft }))} />
    </>
  );
}
