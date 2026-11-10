"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { LoginForm } from "@/components/auth/login-form";
import { MfaFlow } from "@/components/auth/mfa-flow";
import { Button } from "@/components/ui/button";
import { PanelState } from "@/components/panel/panel-state";
import { getIdentity, logout } from "@/lib/api/auth";
import { ApiError, isMfaGate } from "@/lib/api/client";
import { acceptInvitation, previewInvitation, type InvitationPreview } from "@/lib/api/invitations";
import { invitationTokenFromFragment, validInvitationPassword } from "@/lib/api/invitation-rules";
import { clinicRoleLabel } from "@/lib/clinic-role";

const invalidMessage = "O convite está inválido ou expirou. Peça um novo link a quem enviou o convite.";
const inputClass = "h-12 w-full rounded-lg border border-slate-400 bg-white px-3 text-base focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#01247d] aria-invalid:border-red-700";

type Ready = {
  kind: "ready";
  revision: number;
  preview: InvitationPreview;
  needsLogin: boolean;
  mismatch?: boolean;
  error?: string;
  field?: "password" | "displayName";
  conflict?: boolean;
};

type State =
  | { kind: "loading" }
  | { kind: "mfa"; revision: number }
  | { kind: "invalid" | "error" | "conflict"; message: string }
  | Ready;

export function InvitationFlow() {
  const router = useRouter();
  const link = useRef<{ token: string | null; revision: number } | undefined>(undefined);
  const busy = useRef(false);
  const mounted = useRef(false);
  const [state, setState] = useState<State>({ kind: "loading" });
  const [attempt, setAttempt] = useState(0);
  const [pending, setPending] = useState<"accept" | "logout" | null>(null);

  const reload = useCallback(() => {
    setState({ kind: "loading" });
    setAttempt((value) => value + 1);
  }, []);

  useEffect(() => {
    mounted.current = true;
    function readFragment() {
      link.current = {
        token: invitationTokenFromFragment(window.location.hash),
        revision: (link.current?.revision ?? 0) + 1,
      };
      if (window.location.hash) {
        window.history.replaceState(window.history.state, "", window.location.pathname);
      }
    }
    function newLink() {
      if (!window.location.hash) return;
      readFragment();
      reload();
    }
    if (link.current === undefined) readFragment();
    window.addEventListener("hashchange", newLink);
    return () => {
      mounted.current = false;
      window.removeEventListener("hashchange", newLink);
    };
  }, [reload]);

  useEffect(() => {
    const controller = new AbortController();
    const current = link.current;

    async function load(): Promise<State> {
      if (!current?.token) return { kind: "invalid", message: invalidMessage };

      const preview = await previewInvitation(current.token, controller.signal);
      let hasSession = true;
      try {
        await getIdentity(controller.signal);
      } catch (error) {
        if (!(error instanceof ApiError && error.status === 401)) throw error;
        hasSession = false;
      }
      const mismatch = hasSession && !preview.requiresLogin;
      return {
        kind: "ready",
        preview,
        revision: current.revision,
        needsLogin: preview.requiresLogin && !hasSession,
        mismatch,
        error: mismatch ? "Saia da conta atual para criar a conta deste convite." : undefined,
      };
    }

    void load().then(
      (next) => {
        if (!controller.signal.aborted && link.current === current) setState(next);
      },
      (error: unknown) => {
        if (controller.signal.aborted || link.current !== current) return;
        if (current && isMfaGate(error)) {
          setState({ kind: "mfa", revision: current.revision });
        } else if (error instanceof ApiError && error.code === "INVITATION_INVALID") {
          if (current) current.token = null;
          setState({ kind: "invalid", message: invalidMessage });
        } else {
          setState({
            kind: error instanceof ApiError && error.code === "STATE_CONFLICT" ? "conflict" : "error",
            message: error instanceof ApiError ? error.message : "Não foi possível carregar o convite.",
          });
        }
      },
    );
    return () => controller.abort();
  }, [attempt]);

  async function accept(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const current = link.current;
    if (state.kind !== "ready" || state.needsLogin || state.mismatch || state.conflict ||
        !current?.token || state.revision !== current.revision || busy.current) return;

    const form = event.currentTarget;
    const values = new FormData(form);
    const account = state.preview.requiresLogin ? undefined : {
      displayName: String(values.get("displayName") ?? "").trim(),
      password: String(values.get("password") ?? ""),
    };
    if (account && !account.displayName) {
      setState({ ...state, field: "displayName", error: "Informe seu nome." });
      form.querySelector<HTMLInputElement>("#invitation-name")?.focus();
      return;
    }
    if (account && !validInvitationPassword(account.password)) {
      setState({ ...state, field: "password", error: "Use pelo menos 12 caracteres e no máximo 72 bytes." });
      form.querySelector<HTMLInputElement>("#invitation-password")?.focus();
      return;
    }

    busy.current = true;
    setPending("accept");
    setState({ ...state, error: undefined, field: undefined });
    let accepted = false;
    try {
      await acceptInvitation(current.token, account);
      if (!mounted.current || link.current !== current) return;
      current.token = null;
      accepted = true;
      form.reset();
      router.replace("/login?motivo=convite-aceito");
    } catch (error) {
      if (!mounted.current || link.current !== current) return;
      if (isMfaGate(error)) {
        setState({ kind: "mfa", revision: current.revision });
        return;
      }
      const code = error instanceof ApiError ? error.code : null;
      switch (code) {
        case "INVITATION_INVALID":
          current.token = null;
          setState({ kind: "invalid", message: invalidMessage });
          break;
        case "INVITATION_LOGIN_REQUIRED":
          setState({ ...state, preview: { ...state.preview, requiresLogin: true }, needsLogin: true,
            error: "Entre com a conta que recebeu o convite para continuar." });
          break;
        case "INVITATION_ACCOUNT_MISMATCH":
          setState({ ...state, mismatch: true, error: "O convite foi enviado para outra conta. Saia para continuar com a conta correta." });
          break;
        case "MEMBER_ALREADY_ACTIVE":
          current.token = null;
          setState({ kind: "invalid", message: "Você já tem o acesso oferecido por este convite. Entre no painel para continuar." });
          break;
        case "AUTHENTICATION_REQUIRED":
        case "SESSION_REVOKED":
          reload();
          break;
        case "STATE_CONFLICT":
          setState({ ...state, conflict: true, error: error instanceof ApiError ? error.message : undefined });
          break;
        default: {
          const field = error instanceof ApiError && error.code === "INVALID_INPUT"
            ? error.fields.find((value) => value === "password" || value === "displayName")
            : undefined;
          setState({ ...state, field, error: error instanceof ApiError ? error.message : "Não foi possível aceitar o convite." });
          if (field) form.querySelector<HTMLInputElement>(`[name="${field}"]`)?.focus();
        }
      }
    } finally {
      busy.current = false;
      if (mounted.current && !accepted) setPending(null);
    }
  }

  async function changeAccount(ready: Ready) {
    const current = link.current;
    if (busy.current || !current?.token || current.revision !== ready.revision) return;
    busy.current = true;
    setPending("logout");
    try {
      await logout();
      if (mounted.current && link.current === current) reload();
    } catch (error) {
      if (!mounted.current || link.current !== current) return;
      if (error instanceof ApiError && error.status === 401) reload();
      else setState({ ...ready, error: error instanceof ApiError ? error.message : "Não foi possível sair. Tente novamente." });
    } finally {
      busy.current = false;
      if (mounted.current) setPending(null);
    }
  }

  if (state.kind === "loading") {
    return <p role="status" className="mt-4 text-sm text-slate-700">Verificando convite…</p>;
  }
  if (state.kind === "mfa") {
    return (
      <div className="mt-5 space-y-4">
        <p className="text-sm text-slate-700">Confirme a segurança da sua conta para continuar com o convite.</p>
        <MfaFlow key={state.revision} onComplete={reload} onSessionLost={reload} />
      </div>
    );
  }
  if (state.kind !== "ready") {
    return (
      <div className="mt-5 space-y-4">
        <PanelState
          kind={state.kind === "conflict" ? "conflict" : "error"}
          title={state.kind === "invalid" ? "Convite indisponível" : "Não foi possível verificar o convite"}
          message={state.message}
          onRetry={state.kind === "invalid" ? undefined : reload}
        />
        <Link href="/login" className="inline-flex min-h-11 items-center rounded-sm text-sm font-semibold text-[#01247d] hover:underline focus-visible:outline-2 focus-visible:outline-offset-4">Ir para o login</Link>
      </div>
    );
  }

  return (
    <>
      <dl className="mt-5 rounded-lg border border-slate-200 bg-slate-50 p-4">
        <dt className="text-sm text-slate-700">{state.preview.kind === "AGENCY" ? "Agência" : "Clínica"}</dt>
        <dd className="mt-1 font-semibold wrap-anywhere">{state.preview.targetName}</dd>
        <dt className="mt-3 text-sm text-slate-700">Papel</dt>
        <dd className="mt-1 text-sm font-semibold wrap-anywhere">
          {state.preview.kind === "AGENCY" ? "Admin da agência" : clinicRoleLabel(state.preview.role)}
        </dd>
      </dl>
      {state.needsLogin && !state.mismatch ? (
        <>
          <p className="mt-4 text-sm text-slate-700">Entre com a conta que recebeu o convite. Depois, confirme o aceite.</p>
          <LoginForm onAuthenticated={() => {
            if (mounted.current && link.current?.revision === state.revision) {
              reload();
            }
          }} />
        </>
      ) : (
        <form method="post" onSubmit={accept} className="mt-5 space-y-4">
          {!state.preview.requiresLogin && !state.mismatch && (
            <>
              <div>
                <label htmlFor="invitation-name" className="mb-2 block text-sm font-semibold">Seu nome</label>
                <input id="invitation-name" name="displayName" autoComplete="name" required maxLength={160} readOnly={Boolean(pending)} aria-invalid={state.field === "displayName"} aria-describedby={state.field === "displayName" ? "invitation-error" : undefined} className={inputClass} />
              </div>
              <div>
                <label htmlFor="invitation-password" className="mb-2 block text-sm font-semibold">Crie uma senha</label>
                <input id="invitation-password" name="password" type="password" autoComplete="new-password" required readOnly={Boolean(pending)} aria-invalid={state.field === "password"} aria-describedby={state.field === "password" ? "password-rules invitation-error" : "password-rules"} className={inputClass} />
                <p id="password-rules" className="mt-2 text-sm text-slate-700">Use pelo menos 12 caracteres e até 72 bytes. Caracteres como emojis podem ocupar mais de um byte.</p>
              </div>
            </>
          )}
          <div role="alert" id="invitation-error">
            {state.error && <p className="rounded-lg bg-red-50 px-3 py-2.5 text-sm text-red-900">{state.error}</p>}
          </div>
          {state.mismatch ? (
            <Button type="button" disabled={Boolean(pending)} onClick={() => changeAccount(state)} className="min-h-12 w-full">
              {pending === "logout" ? "Saindo…" : "Sair para trocar de conta"}
            </Button>
          ) : state.conflict ? (
            <Button type="button" onClick={reload} className="min-h-12 w-full">Recarregar convite</Button>
          ) : (
            <Button type="submit" disabled={Boolean(pending)} className="min-h-12 w-full">
              {pending === "accept" ? "Aceitando…" : "Aceitar convite"}
            </Button>
          )}
        </form>
      )}
    </>
  );
}
