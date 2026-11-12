"use client";

import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { flushSync } from "react-dom";
import { usePathname, useRouter } from "next/navigation";
import { Building2 } from "lucide-react";
import { getCurrentClinic, getIdentity, getPanelAccess, logout, type CurrentClinic } from "@/lib/api/auth";
import { ApiError, onMfaGate, onSessionEnd, sessionEndMessage } from "@/lib/api/client";
import { getMfaStatus } from "@/lib/api/mfa";
import { onClinicChange } from "@/lib/api/clinic-session";
import { clinicRoleLabel } from "@/lib/clinic-role";
import { PanelState } from "@/components/panel/panel-state";
import { Card, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { ClinicPicker } from "@/components/panel/clinic-picker";
import { PanelNavigation } from "@/components/panel/panel-navigation";
import { ClinicContext } from "@/components/panel/clinic-context";
import { AgencyContext } from "@/components/agency/agency-context";

type SessionCheck =
  | { kind: "checking" }
  | { kind: "confirmed"; email: string; subjectId: string; access: Awaited<ReturnType<typeof getPanelAccess>>; clinic: (CurrentClinic & { name: string }) | null }
  | { kind: "redirecting" | "forbidden" | "error" | "conflict"; message: string };

export function PanelShell({ children }: { children: ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const [check, setCheck] = useState<SessionCheck>({ kind: "checking" });
  const [attempt, setAttempt] = useState(0);
  const [choosingClinic, setChoosingClinic] = useState(false);
  const signingOutRef = useRef(false);
  const [signingOut, setSigningOut] = useState(false);
  const [logoutError, setLogoutError] = useState<{ message: string; conflict: boolean }>();

  const retry = useCallback(() => {
    setChoosingClinic(false);
    setCheck({ kind: "checking" });
    setAttempt((previous) => previous + 1);
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    const unsubscribe = onSessionEnd((reason) => {
      controller.abort();
      setCheck({ kind: "redirecting", message: sessionEndMessage(reason) });
      router.replace(reason === "SESSION_REVOKED" ? "/login?motivo=sessao-encerrada" : "/login");
    });
    function openSecurity() {
      controller.abort();
      setCheck({ kind: "redirecting", message: "Continue com a segurança da conta." });
      router.replace("/seguranca");
    }
    const unsubscribeMfa = onMfaGate(openSecurity);
    const unsubscribeClinic = onClinicChange(() => {
      if (window.location.pathname === "/agencia") return;
      controller.abort();
      if (window.location.pathname.startsWith("/app/contatos/")) router.replace("/app/contatos");
      if (window.location.pathname.startsWith("/app/funil/")) router.replace("/app/funil");
      if (window.location.pathname.startsWith("/app/vendas/")) router.replace("/app/vendas");
      retry();
    });
    function suspend(event: PageTransitionEvent) {
      if (!event.persisted) return;
      controller.abort();
      flushSync(() => setCheck({ kind: "checking" }));
    }
    function resume(event: PageTransitionEvent) {
      if (event.persisted) retry();
    }
    window.addEventListener("pagehide", suspend);
    window.addEventListener("pageshow", resume);

    async function load() {
      const mfa = await getMfaStatus(controller.signal);
      if (mfa.state !== "COMPLETE") {
        if (!controller.signal.aborted) openSecurity();
        return;
      }
      const [identity, access] = await Promise.all([getIdentity(controller.signal), getPanelAccess(controller.signal)]);
      const current = access.clinics.length > 0 && window.location.pathname !== "/agencia" ? await getCurrentClinic(controller.signal) : null;
      const membership = access.clinics.find((clinic) => clinic.tenantId === current?.tenantId);
      if (current && !membership) {
        throw new ApiError(409, "STATE_CONFLICT", "A lista de clínicas mudou. Recarregue os dados para continuar.");
      }
      if (!controller.signal.aborted) {
        setCheck({ kind: "confirmed", ...identity, access, clinic: current && membership ? { ...current, name: membership.clinicName } : null });
      }
    }

    void load().catch(
      (error: unknown) => {
        if (controller.signal.aborted) return;
        const forbidden = error instanceof ApiError && error.status === 403 && error.code !== "CSRF_INVALID";
        setCheck({
          kind: error instanceof ApiError && error.code === "STATE_CONFLICT"
            ? "conflict"
            : forbidden ? "forbidden" : "error",
          message: error instanceof ApiError ? error.message : "Não foi possível verificar a sessão.",
        });
      },
    );

    return () => {
      unsubscribe();
      unsubscribeMfa();
      unsubscribeClinic();
      window.removeEventListener("pagehide", suspend);
      window.removeEventListener("pageshow", resume);
      controller.abort();
    };
  }, [attempt, retry, router]);

  async function signOut() {
    if (signingOutRef.current) return;
    signingOutRef.current = true;
    setSigningOut(true);
    setLogoutError(undefined);

    try {
      await logout();
      router.replace("/login?motivo=saiu");
    } catch (error) {
      signingOutRef.current = false;
      setSigningOut(false);
      setLogoutError({
        message: error instanceof ApiError ? error.message : "Não foi possível sair. Tente novamente.",
        conflict: error instanceof ApiError && error.code === "STATE_CONFLICT",
      });
      if (!(error instanceof ApiError && error.status === 401)) retry();
    }
  }

  const confirmed = check.kind === "confirmed" ? check : null;
  const clinic = confirmed?.clinic;
  const agencyArea = pathname === "/agencia";
  const selecting = !agencyArea && Boolean(confirmed?.access.clinics.length && (!clinic || choosingClinic));
  const overview = pathname === "/app";
  const redirectToAgency = overview && confirmed?.access.kind === "agency";
  const contacts = pathname === "/app/contatos" || pathname.startsWith("/app/contatos/");
  const funnel = pathname === "/app/funil" || pathname.startsWith("/app/funil/");
  const catalog = pathname === "/app/catalogo";
  const sales = pathname === "/app/vendas" || pathname.startsWith("/app/vendas/");
  const team = pathname === "/app/equipe";
  const reports = pathname === "/app/relatorios";
  const tasks = pathname === "/app/tarefas";
  const canViewContacts = clinic?.permissions.CONTACT_READ === "ALL";
  const canViewTeam = clinic?.permissions.TEAM_VIEW === "ALL";
  const canReadCommercial = clinic?.permissions.LEAD_READ === "ALL";
  const canViewReport = clinic?.permissions.MONTHLY_REPORT_VIEW === "ALL";
  const canViewTasks = clinic?.permissions.TASK_READ === "ALL";
  const commercial = funnel || catalog || sales;
  const sectionName = team ? "a equipe" : funnel ? "o funil" : catalog ? "o catálogo" : sales ? "vendas" : reports ? "relatórios" : tasks ? "tarefas" : "contatos";

  useEffect(() => {
    if (redirectToAgency) router.replace("/agencia");
  }, [redirectToAgency, router]);

  return (
    <PanelNavigation
      email={confirmed?.email}
      clinic={clinic ? { name: clinic.name, role: clinicRoleLabel(clinic.role) } : undefined}
      canSwitchClinic={!agencyArea && Boolean(confirmed && (confirmed.access.clinics.length > 1 || (!clinic && selecting)))}
      hasClinicArea={Boolean(confirmed?.access.clinics.length)}
      hasAgencies={Boolean(confirmed?.access.agencies.length)}
      canViewContacts={canViewContacts && !selecting}
      canViewTeam={canViewTeam && !selecting}
      canReadCommercial={canReadCommercial && !selecting}
      canViewReport={canViewReport && !selecting}
      canViewTasks={canViewTasks && !selecting}
      currentPath={pathname}
      choosingClinic={selecting}
      signingOut={signingOut}
      onChooseClinic={() => setChoosingClinic(true)}
      onOverview={() => { if (choosingClinic) retry(); }}
      onSignOut={signOut}
    >
      <main id="conteudo-painel" tabIndex={-1} className="mx-auto max-w-7xl scroll-mt-20 space-y-6 px-4 py-7 sm:px-7 sm:py-9">
        {!agencyArea && !redirectToAgency && (overview || selecting || !clinic || signingOut) && <div className="space-y-2">
          <p className="text-xs font-bold tracking-widest text-[#315683] uppercase">Área de trabalho</p>
          <h1 className="font-[family-name:var(--font-branly)] text-3xl font-extrabold tracking-tight text-balance sm:text-4xl">
            {selecting && !signingOut ? "Selecionar clínica" : "Visão geral"}
          </h1>
          <p className="max-w-prose text-sm leading-relaxed text-slate-600">
            {selecting && !signingOut ? "Escolha a clínica em que você quer trabalhar nesta sessão." : "Acompanhe o contexto da sua sessão e acesse as áreas disponíveis para sua conta."}
          </p>
        </div>}
        {check.kind === "checking" && <PanelState kind="loading" title="Verificando a sessão…" />}
        {check.kind === "redirecting" && <PanelState kind="loading" title="Redirecionando…" message={check.message} />}
        {check.kind === "forbidden" && <PanelState kind="forbidden" title="Permissão negada" message={check.message} onRetry={retry} />}
        {check.kind === "error" && <PanelState kind="error" title="Não foi possível abrir o painel" message={check.message} onRetry={retry} />}
        {check.kind === "conflict" && <PanelState kind="conflict" title="Os dados precisam ser atualizados" message={check.message} onRetry={retry} />}
        {check.kind === "confirmed" && (
          signingOut ? <PanelState kind="loading" title="Encerrando a sessão…" /> : (
            <>
              {logoutError && (
                <PanelState
                  kind={logoutError.conflict ? "conflict" : "error"}
                  title="Não foi possível sair"
                  message={logoutError.message}
                  onRetry={logoutError.conflict ? () => { setLogoutError(undefined); retry(); } : undefined}
                />
              )}
              {redirectToAgency && <PanelState kind="loading" title="Abrindo a carteira da agência…" />}
              {agencyArea && (
                check.access.agencies.length ? (
                  <AgencyContext.Provider value={{ agencies: check.access.agencies, subjectId: check.subjectId, refresh: retry }}>
                    {children}
                  </AgencyContext.Provider>
                ) : <PanelState kind="forbidden" title="Sem acesso à carteira da agência" message="Sua conta não administra uma agência ativa." />
              )}
              {!agencyArea && check.access.kind === "none" && (
                <PanelState kind="empty" title="Sem acesso a clínicas ou agências" message="Sua conta está ativa. Se você esperava ter acesso, peça a um gestor para verificar seu vínculo." />
              )}
              {!agencyArea && check.access.clinics.length > 0 && (
                selecting ? (
                  <Card aria-label="Clínicas disponíveis" className="p-4 sm:p-6">
                    <ClinicPicker
                      clinics={check.access.clinics} currentId={check.clinic?.tenantId} onRefresh={retry}
                      onSelected={() => {
                        if (contacts) router.replace("/app/contatos");
                        if (funnel) router.replace("/app/funil");
                        if (sales) router.replace("/app/vendas");
                        retry();
                      }}
                    />
                  </Card>
                ) : overview ? (
                  <div className="space-y-5">
                    {check.clinic && (
                      <Card aria-labelledby="clinic-card-title">
                        <CardHeader className="flex items-center gap-3 space-y-0">
                          <span className="grid size-10 place-items-center rounded-lg bg-[#e9f0fd] text-[#244d8c]">
                            <Building2 size={19} aria-hidden="true" />
                          </span>
                          <div className="min-w-0 flex-1">
                            <CardTitle id="clinic-card-title">Clínica em uso</CardTitle>
                            <CardDescription className="wrap-anywhere">{check.clinic.name}</CardDescription>
                          </div>
                          <span className="hidden w-fit max-w-full rounded-md bg-slate-100 px-2 py-1 text-xs font-semibold text-slate-700 wrap-anywhere sm:inline-flex">{clinicRoleLabel(check.clinic.role)}</span>
                        </CardHeader>
                      </Card>
                    )}
                    {check.clinic?.permissions.RECEPTION_DASHBOARD_VIEW === "ALL" && (
                      <ClinicContext.Provider key={`${check.clinic.tenantId}:${check.subjectId}`}
                        value={{ clinic: check.clinic, subjectId: check.subjectId, refresh: retry }}>
                        {children}
                      </ClinicContext.Provider>
                    )}
                  </div>
                ) : check.clinic && (
                  ((!contacts || canViewContacts) && (!team || canViewTeam) && (!commercial || canReadCommercial) && (!reports || canViewReport) && (!tasks || canViewTasks)) ? (
                    <ClinicContext.Provider key={`${check.clinic.tenantId}:${check.subjectId}`} value={{ clinic: check.clinic, subjectId: check.subjectId, refresh: retry }}>
                      {children}
                    </ClinicContext.Provider>
                  ) : <PanelState kind="forbidden" title={`Sem permissão para acessar ${sectionName}`} message="Confira as permissões do seu vínculo com esta clínica." />
                )
              )}
              {(contacts || team || commercial || reports || tasks) && check.access.kind === "agency" && (
                <PanelState kind="forbidden" title={`Selecione uma clínica para acessar ${sectionName}`} message="Sua conta não tem vínculo ativo com uma clínica." />
              )}
            </>
          )
        )}
      </main>
    </PanelNavigation>
  );
}
