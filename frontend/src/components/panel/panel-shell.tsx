"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { useRouter } from "next/navigation";
import { BriefcaseBusiness, Building2 } from "lucide-react";
import { getCurrentClinic, getIdentity, getPanelAccess, logout, type CurrentClinic } from "@/lib/api/auth";
import { ApiError, onMfaGate, onSessionEnd, sessionEndMessage } from "@/lib/api/client";
import { getMfaStatus } from "@/lib/api/mfa";
import { clinicRoleLabel } from "@/lib/clinic-role";
import { PanelState } from "@/components/panel/panel-state";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { ClinicPicker } from "@/components/panel/clinic-picker";
import { PanelNavigation } from "@/components/panel/panel-navigation";

type SessionCheck =
  | { kind: "checking" }
  | { kind: "confirmed"; email: string; access: Awaited<ReturnType<typeof getPanelAccess>>; clinic: (CurrentClinic & { name: string }) | null }
  | { kind: "redirecting" | "forbidden" | "error" | "conflict"; message: string };

export function PanelShell({ children }: { children: ReactNode }) {
  const router = useRouter();
  const [check, setCheck] = useState<SessionCheck>({ kind: "checking" });
  const [attempt, setAttempt] = useState(0);
  const [choosingClinic, setChoosingClinic] = useState(false);
  const signingOutRef = useRef(false);
  const [signingOut, setSigningOut] = useState(false);
  const [logoutError, setLogoutError] = useState<{ message: string; conflict: boolean }>();

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

    async function load() {
      const mfa = await getMfaStatus(controller.signal);
      if (mfa.state !== "COMPLETE") {
        if (!controller.signal.aborted) openSecurity();
        return;
      }
      const [identity, access] = await Promise.all([getIdentity(controller.signal), getPanelAccess(controller.signal)]);
      const current = access.clinics.length > 0 ? await getCurrentClinic(controller.signal) : null;
      const membership = access.clinics.find((clinic) => clinic.tenantId === current?.tenantId);
      if (current && !membership) {
        throw new ApiError(409, "STATE_CONFLICT", "A lista de clínicas mudou. Recarregue os dados para continuar.");
      }
      if (!controller.signal.aborted) {
        setCheck({ kind: "confirmed", email: identity.email, access, clinic: current && membership ? { ...current, name: membership.clinicName } : null });
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
      controller.abort();
    };
  }, [attempt, router]);

  function retry() {
    setChoosingClinic(false);
    setCheck({ kind: "checking" });
    setAttempt((previous) => previous + 1);
  }

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
  const selecting = Boolean(confirmed?.access.clinics.length && (!clinic || choosingClinic));

  return (
    <PanelNavigation
      email={confirmed?.email}
      clinic={clinic ? { name: clinic.name, role: clinicRoleLabel(clinic.role) } : undefined}
      canSwitchClinic={Boolean(confirmed && (confirmed.access.clinics.length > 1 || (!clinic && selecting)))}
      hasAgencies={Boolean(confirmed?.access.agencies.length)}
      choosingClinic={selecting}
      signingOut={signingOut}
      onChooseClinic={() => setChoosingClinic(true)}
      onOverview={() => { if (choosingClinic) retry(); }}
      onSignOut={signOut}
    >
      <main id="conteudo-painel" tabIndex={-1} className="mx-auto max-w-7xl scroll-mt-20 space-y-6 px-4 py-7 sm:px-7 sm:py-9">
        <div className="space-y-2">
          <p className="text-xs font-bold tracking-widest text-[#315683] uppercase">Área de trabalho</p>
          <h1 className="font-[family-name:var(--font-branly)] text-3xl font-extrabold tracking-tight text-balance sm:text-4xl">
            {selecting && !signingOut ? "Selecionar clínica" : "Visão geral"}
          </h1>
          <p className="max-w-prose text-sm leading-relaxed text-slate-600">
            {selecting && !signingOut ? "Escolha a clínica em que você quer trabalhar nesta sessão." : "Acompanhe o contexto da sua sessão e acesse as áreas disponíveis para sua conta."}
          </p>
        </div>
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
              {check.access.kind === "none" && (
                <PanelState kind="empty" title="Sem acesso a clínicas ou agências" message="Sua conta está ativa. Se você esperava ter acesso, peça a um gestor para verificar seu vínculo." />
              )}
              {check.access.clinics.length > 0 && (
                selecting ? (
                  <Card aria-label="Clínicas disponíveis" className="p-4 sm:p-6">
                    <ClinicPicker clinics={check.access.clinics} currentId={check.clinic?.tenantId} onRefresh={retry} />
                  </Card>
                ) : (
                  <div className="grid gap-4 md:grid-cols-2">
                    {check.clinic && (
                      <Card aria-labelledby="clinic-card-title">
                        <CardHeader>
                          <span className="grid size-10 place-items-center rounded-lg bg-[#e9f0fd] text-[#244d8c]">
                            <Building2 size={19} aria-hidden="true" />
                          </span>
                          <CardTitle id="clinic-card-title" className="pt-1">Clínica em uso</CardTitle>
                          <CardDescription>{check.clinic.name}</CardDescription>
                          <span className="inline-flex w-fit max-w-full rounded-md bg-slate-100 px-2 py-1 text-xs font-semibold text-slate-700 wrap-anywhere">{clinicRoleLabel(check.clinic.role)}</span>
                        </CardHeader>
                      </Card>
                    )}
                    {children}
                  </div>
                )
              )}
              {check.access.agencies.length > 0 && (
                <Card id="suas-agencias" tabIndex={-1} className="scroll-mt-22 focus-visible:outline-2" aria-labelledby="agency-access">
                  <CardHeader>
                    <span className="grid size-10 place-items-center rounded-lg bg-[#e9f0fd] text-[#244d8c]">
                      <BriefcaseBusiness size={19} aria-hidden="true" />
                    </span>
                    <CardTitle id="agency-access" className="pt-1">Minhas agências</CardTitle>
                    <CardDescription>Agências vinculadas à sua conta.</CardDescription>
                  </CardHeader>
                  <CardContent>
                    <ul className="divide-y divide-border rounded-lg border border-border">
                      {check.access.agencies.map((agency) => (
                        <li className="wrap-anywhere px-4 py-3 text-sm font-medium" key={agency.agencyId}>{agency.name}</li>
                      ))}
                    </ul>
                  </CardContent>
                </Card>
              )}
            </>
          )
        )}
      </main>
    </PanelNavigation>
  );
}
