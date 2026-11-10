"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { Dialog } from "@base-ui/react/dialog";
import { MailPlus } from "lucide-react";
import { useClinic } from "@/components/panel/clinic-context";
import { PanelState } from "@/components/panel/panel-state";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { withSelectedClinic } from "@/lib/api/auth";
import { ApiError } from "@/lib/api/client";
import { loginRetryDeadline } from "@/lib/api/retry-after";
import {
  changeMemberRole, deactivateMember, inviteMember, listMembers, listTeamInvitations, resendTeamInvitation,
  revokeTeamInvitation, teamRoles, type Member, type MemberFilters, type TeamInvitation, type TeamPage, type TeamRole,
} from "@/lib/api/team";
import { invitationDeliveryMessage, teamErrorMessage } from "@/lib/api/team-feedback";
import { clinicRoleLabel } from "@/lib/clinic-role";

const control = "min-h-11 w-full rounded-lg border border-slate-400 bg-white px-3 py-2 text-base text-slate-950 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary";
const dates = new Intl.DateTimeFormat("pt-BR", { dateStyle: "short", timeStyle: "short", timeZone: "UTC" });

type Action =
  | { kind: "invite" }
  | { kind: "role" | "deactivate"; member: Member }
  | { kind: "resend" | "revoke"; invitation: TeamInvitation };

const actionLabels = {
  invite: "Enviar convite", role: "Salvar papel", deactivate: "Desativar membro", resend: "Reenviar convite", revoke: "Revogar convite",
};

function mustRefresh(error: unknown) {
  return error instanceof ApiError && ["CLINIC_ACCESS_DENIED", "CLINIC_NOT_SELECTED", "SESSION_CONTEXT_CHANGED"].includes(error.code);
}

function useTeamPage<T>(load: (signal: AbortSignal) => Promise<T>) {
  const { clinic, refresh, subjectId } = useClinic();
  const [revision, setRevision] = useState(0);
  const [result, setResult] = useState<{ load: typeof load; revision: number; data?: T; error?: string }>();
  useEffect(() => {
    const controller = new AbortController();
    void withSelectedClinic(clinic.tenantId, () => load(controller.signal), controller.signal, subjectId).then(
      (data) => { if (!controller.signal.aborted) setResult({ load, revision, data }); },
      (error: unknown) => {
        if (controller.signal.aborted) return;
        if (mustRefresh(error)) refresh();
        else setResult({ load, revision, error: error instanceof ApiError ? error.message : "Tente carregar a lista novamente." });
      },
    );
    return () => controller.abort();
  }, [clinic.tenantId, load, refresh, revision, subjectId]);
  return {
    result: result?.load === load && result.revision === revision ? result : undefined,
    reload: () => setRevision((value) => value + 1),
  };
}

function Pagination({ page, name, onPage }: { page: TeamPage<unknown>; name: string; onPage: (page: number) => void }) {
  const maxPage = Math.min(Math.max(1, Math.ceil(page.total / page.pageSize)), Math.floor(10_000 / page.pageSize) + 1);
  if (maxPage === 1 && page.page === 1) return null;
  return (
    <nav aria-label={name} className="flex flex-wrap items-center justify-between gap-3 border-t border-border pt-3">
      <p role="status" className="text-sm text-slate-600">Página {page.page} de {maxPage} · {page.total.toLocaleString("pt-BR")} registros</p>
      <div className="flex gap-2">
        <Button type="button" variant="outline" className="min-h-11" disabled={page.page === 1} onClick={() => onPage(Math.min(maxPage, page.page - 1))}>Anterior</Button>
        <Button type="button" variant="outline" className="min-h-11" disabled={page.page >= maxPage} onClick={() => onPage(page.page + 1)}>Próxima</Button>
      </div>
    </nav>
  );
}

function Entry({ title, subtitle, detail, children }: { title: string; subtitle?: string; detail: string; children?: ReactNode }) {
  return (
    <li className="flex flex-wrap items-center justify-between gap-3 py-3">
      <div className="min-w-0 space-y-1 wrap-anywhere">
        <p className="font-semibold">{title}</p>
        {subtitle && <p className="text-sm text-slate-700">{subtitle}</p>}
        <p className="text-sm text-slate-600">{detail}</p>
      </div>
      {children && <div className="flex flex-wrap gap-2">{children}</div>}
    </li>
  );
}

function ActionDialog({ action, roles, blockedUntil, onDismiss, onSuccess, onLimit }: {
  action: Action; roles: readonly TeamRole[]; blockedUntil: number; onDismiss: () => void;
  onSuccess: (message: string, newInvitation: boolean) => void; onLimit: (deadline: number) => void;
}) {
  const { clinic, refresh, subjectId } = useClinic();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [uncertain, setUncertain] = useState(false);
  const inFlight = useRef(false);
  const alive = useRef(false);
  const sending = action.kind === "invite" || action.kind === "resend";
  const roleForm = action.kind === "invite" || action.kind === "role";
  const target = "member" in action ? action.member.displayName || action.member.email : "invitation" in action ? action.invitation.email : undefined;

  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; };
  }, []);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (inFlight.current || uncertain || (sending && Date.now() < blockedUntil)) return;
    const form = new FormData(event.currentTarget);
    const email = String(form.get("email") ?? "").trim();
    const role = form.get("role") as TeamRole;
    if (roleForm && !roles.includes(role)) return;
    inFlight.current = true;
    setPending(true);
    setError("");
    try {
      const message = await withSelectedClinic(clinic.tenantId, async () => {
        switch (action.kind) {
          case "invite": return invitationDeliveryMessage((await inviteMember(email, role)).delivery);
          case "resend": return invitationDeliveryMessage((await resendTeamInvitation(action.invitation.invitationId)).delivery);
          case "revoke":
            await revokeTeamInvitation(action.invitation.invitationId);
            return "Convite revogado.";
          case "role":
            await changeMemberRole(action.member.subjectId, role);
            return "Papel atualizado.";
          case "deactivate":
            await deactivateMember(action.member.subjectId);
            return "Membro desativado. O histórico foi preservado.";
        }
      }, undefined, subjectId);
      if (alive.current) onSuccess(message, action.kind === "invite");
    } catch (cause) {
      if (!alive.current) return;
      if (mustRefresh(cause)) refresh();
      else if (cause instanceof ApiError && cause.status === 429) {
        if (cause.retryAfter) onLimit(loginRetryDeadline(cause.retryAfter, Date.now()));
        setError(teamErrorMessage(cause.code, "Muitas tentativas. Aguarde antes de tentar novamente."));
      } else if (!(cause instanceof ApiError) || cause.status === 0 || cause.status >= 500 || (cause.status >= 200 && cause.status < 300)) {
        setUncertain(true);
        setError("Não foi possível confirmar o resultado. Atualize a lista e confira a situação antes de repetir.");
      } else {
        setError(teamErrorMessage(cause.code, cause.message));
      }
    } finally {
      inFlight.current = false;
      if (alive.current) setPending(false);
    }
  }

  return (
    <Dialog.Root open onOpenChange={(open) => {
      if (open || inFlight.current) return;
      if (uncertain) onSuccess("", false);
      else onDismiss();
    }}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-slate-950/55" />
        <Dialog.Popup className="fixed top-1/2 left-1/2 z-50 max-h-[90dvh] w-[calc(100%-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2 space-y-4 overflow-y-auto overscroll-contain rounded-xl border border-border bg-card p-5 shadow-xl sm:p-6">
          <Dialog.Title className="text-xl font-bold">{action.kind === "invite" ? "Convidar para a equipe" : actionLabels[action.kind]}</Dialog.Title>
          <Dialog.Description className="text-sm text-slate-600 wrap-anywhere">
            {action.kind === "invite" ? "A pessoa receberá um link de acesso por e-mail." : target}
            {action.kind === "resend" && " · O link anterior deixará de funcionar."}
            {action.kind === "revoke" && " · A pessoa não poderá aceitar este convite."}
            {action.kind === "deactivate" && " · A pessoa perderá acesso a esta clínica. O histórico será preservado."}
          </Dialog.Description>
          <form method="post" onSubmit={submit} className="space-y-4">
            {action.kind === "invite" && <label className="block space-y-1 text-sm font-medium">E-mail da pessoa
              <input type="email" name="email" autoComplete="off" spellCheck={false} maxLength={254} required disabled={pending || uncertain} className={control} />
            </label>}
            {roleForm && <label className="block space-y-1 text-sm font-medium">Papel na clínica
              <select name="role" className={control} required disabled={pending || uncertain} defaultValue={action.kind === "role" ? action.member.role : ""}>
                <option value="">Selecione um papel</option>
                {roles.map((role) => <option key={role} value={role}>{clinicRoleLabel(role)}</option>)}
              </select>
            </label>}
            {error && <p role="alert" className="rounded-lg bg-red-50 p-3 text-sm text-red-900">{error}</p>}
            {sending && blockedUntil > 0 && <p role="status" className="text-sm text-slate-700">Próximo envio disponível após {dates.format(blockedUntil)} UTC.</p>}
            <div className="flex flex-wrap justify-end gap-2">
              <Dialog.Close render={<Button type="button" variant="outline" className="min-h-11" disabled={pending} />}>Cancelar</Dialog.Close>
              {uncertain
                ? <Button type="button" className="min-h-11" onClick={() => onSuccess("", false)}>Atualizar listas</Button>
                : <Button type="submit" variant={action.kind === "deactivate" || action.kind === "revoke" ? "destructive" : "default"} className="min-h-11" disabled={pending || (sending && blockedUntil > 0)}>
                    {pending ? "Processando…" : actionLabels[action.kind]}
                  </Button>}
            </div>
          </form>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

const initialFilters: MemberFilters = { page: 1, role: "", active: "" };

function Invitations({ roles, setAction, page, onPage }: {
  roles: readonly TeamRole[]; setAction: (action: Action) => void; page: number; onPage: (page: number) => void;
}) {
  const load = useCallback((signal: AbortSignal) => listTeamInvitations(page, signal), [page]);
  const { result, reload } = useTeamPage(load);
  return (
    <Card aria-labelledby="team-invitations">
      <CardHeader><CardTitle id="team-invitations">Convites pendentes</CardTitle></CardHeader>
      <CardContent className="space-y-3">
        {!result && <PanelState kind="loading" title="Carregando convites…" />}
        {result?.error && <PanelState kind="error" title="Não foi possível listar os convites" message={result.error} onRetry={reload} />}
        {result?.data && (
          <>
            {result.data.items.length === 0 ? <p role="status" className="text-sm text-slate-600">Nenhum convite pendente nesta página.</p> : (
              <ul className="divide-y divide-border">
                {result.data.items.map((invitation) => (
                  <Entry key={invitation.invitationId} title={invitation.email} subtitle={clinicRoleLabel(invitation.role)}
                    detail={`${invitation.expired ? "Vencido em" : "Válido até"} ${dates.format(new Date(invitation.expiresAt))} UTC`}>
                    {roles.includes(invitation.role) && <>
                      <Button type="button" variant="outline" className="min-h-11" aria-label={`Reenviar convite para ${invitation.email}`} onClick={() => setAction({ kind: "resend", invitation })}>Reenviar</Button>
                      <Button type="button" variant="outline" className="min-h-11" aria-label={`Revogar convite para ${invitation.email}`} onClick={() => setAction({ kind: "revoke", invitation })}>Revogar</Button>
                    </>}
                  </Entry>
                ))}
              </ul>
            )}
            <Pagination name="Paginação de convites" page={result.data} onPage={onPage} />
          </>
        )}
      </CardContent>
    </Card>
  );
}

export function TeamWorkspace() {
  const { clinic, subjectId } = useClinic();
  const [filters, setFilters] = useState(initialFilters);
  const [invitationPage, setInvitationPage] = useState(1);
  const [invitationVersion, setInvitationVersion] = useState(0);
  const [action, setAction] = useState<Action>();
  const [notice, setNotice] = useState("");
  const [blockedUntil, setBlockedUntil] = useState(0);
  const canManage = clinic.permissions.TEAM_MANAGE === "ALL";
  const roles = clinic.role === "MANAGER" ? teamRoles.slice(2) : teamRoles;
  const load = useCallback((signal: AbortSignal) => listMembers(filters, signal), [filters]);
  const members = useTeamPage(load);

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

  return (
    <>
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="space-y-2">
          <h1 className="font-[family-name:var(--font-branly)] text-3xl font-extrabold tracking-tight sm:text-4xl">Equipe e convites</h1>
          <p className="text-sm text-slate-600">Gerencie quem tem acesso à clínica selecionada.</p>
        </div>
        {canManage && <Button type="button" className="min-h-11 gap-2" onClick={() => setAction({ kind: "invite" })}><MailPlus aria-hidden="true" />Convidar pessoa</Button>}
      </header>
      <p role="status" className={notice ? "rounded-lg border border-border bg-white px-4 py-3 text-sm" : "sr-only"}>{notice}</p>
      <Card aria-labelledby="team-members">
        <CardHeader><CardTitle id="team-members">Membros da equipe</CardTitle></CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="space-y-1 text-sm font-medium">Papel
              <select name="role" className={control} value={filters.role} onChange={(event) => setFilters({ ...filters, page: 1, role: event.target.value as MemberFilters["role"] })}>
                <option value="">Todos os papéis</option>
                {teamRoles.map((role) => <option key={role} value={role}>{clinicRoleLabel(role)}</option>)}
              </select>
            </label>
            <label className="space-y-1 text-sm font-medium">Situação
              <select name="active" className={control} value={filters.active} onChange={(event) => setFilters({ ...filters, page: 1, active: event.target.value as MemberFilters["active"] })}>
                <option value="">Todas as situações</option><option value="true">Ativos</option><option value="false">Inativos</option>
              </select>
            </label>
          </div>
          {!members.result && <PanelState kind="loading" title="Carregando equipe…" />}
          {members.result?.error && <PanelState kind="error" title="Não foi possível listar a equipe" message={members.result.error} onRetry={members.reload} />}
          {members.result?.data && (
            <>
              {members.result.data.items.length === 0 ? <p role="status" className="text-sm text-slate-600">Nenhum membro encontrado com estes filtros.</p> : (
                <ul className="divide-y divide-border">
                  {members.result.data.items.map((member) => (
                    <Entry key={member.subjectId} title={member.displayName || member.email} subtitle={member.displayName ? member.email : undefined}
                      detail={`${clinicRoleLabel(member.role)} · ${member.active ? "Ativo" : "Inativo"}${member.subjectId === subjectId ? " · Você" : ""}`}>
                      {canManage && member.active && member.subjectId !== subjectId && roles.includes(member.role) && <>
                        <Button type="button" variant="outline" className="min-h-11" aria-label={`Alterar papel de ${member.displayName || member.email}`} onClick={() => setAction({ kind: "role", member })}>Alterar papel</Button>
                        <Button type="button" variant="outline" className="min-h-11" aria-label={`Desativar ${member.displayName || member.email}`} onClick={() => setAction({ kind: "deactivate", member })}>Desativar</Button>
                      </>}
                    </Entry>
                  ))}
                </ul>
              )}
              <Pagination name="Paginação de membros" page={members.result.data} onPage={(page) => setFilters({ ...filters, page })} />
            </>
          )}
        </CardContent>
      </Card>
      {canManage && <Invitations key={invitationVersion} roles={roles} setAction={setAction} page={invitationPage} onPage={setInvitationPage} />}
      {action && <ActionDialog action={action} roles={roles} blockedUntil={blockedUntil} onLimit={setBlockedUntil} onDismiss={() => setAction(undefined)}
        onSuccess={(message, newInvitation) => {
          setAction(undefined);
          setNotice(message);
          if (newInvitation) setInvitationPage(1);
          setInvitationVersion((value) => value + 1);
          members.reload();
        }} />}
    </>
  );
}
