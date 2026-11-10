"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { flushSync } from "react-dom";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { RecoveryCodes } from "@/components/auth/recovery-codes";
import { logout } from "@/lib/api/auth";
import { ApiError, isMfaGate, onSessionEnd } from "@/lib/api/client";
import { disableMfa, getMfaStatus, issueRecoveryCodes, setupMfa, verifyMfa, type MfaStatus } from "@/lib/api/mfa";
import { loginRetryDeadline } from "@/lib/api/retry-after";

type Action = "rotate" | "regenerate" | "disable";
type Screen =
  | { kind: "loading" | "unavailable" | "redirecting" }
  | { kind: "ready"; status: MfaStatus }
  | { kind: "setup"; secret: string; status: MfaStatus }
  | { kind: "codes"; codes: string[]; next: "panel" | "login" | "manage" };

const messages: Record<string, string> = {
  MFA_CODE_INVALID: "Código inválido ou já usado. Confira o aplicativo ou tente outro código.",
  MFA_LOCKED: "Muitas tentativas incorretas. Aguarde até a próxima tentativa.",
  MFA_SETUP_EXPIRED: "A chave temporária expirou. Gere outra chave para continuar.",
  MFA_MANDATORY: "O segundo fator é obrigatório para esta conta.",
  MFA_NOT_ENROLLED: "Esta conta ainda não tem um segundo fator cadastrado.",
  MFA_ALREADY_COMPLETE: "O segundo fator já foi verificado nesta sessão. Atualize para continuar.",
};

function CodeForm({
  onSubmit, pending, disabled, recovery = false, label = "Código do aplicativo", submitLabel,
}: {
  onSubmit: (code: string) => void;
  pending: boolean;
  disabled: boolean;
  recovery?: boolean;
  label?: string;
  submitLabel: string;
}) {
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => { input.current?.focus(); }, []);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (disabled || pending) return;
    onSubmit(String(new FormData(event.currentTarget).get("code") ?? "").trim());
  }

  return (
    <form method="post" onSubmit={submit} className="space-y-3">
      <label className="block text-sm font-semibold">
        <span>{label}</span>
        <input
          ref={input}
          name="code" required readOnly={pending} spellCheck={false}
          autoComplete={recovery ? "off" : "one-time-code"}
          inputMode={recovery ? "text" : "numeric"}
          pattern={recovery ? undefined : "[0-9]{6}"}
          maxLength={recovery ? 64 : 6}
          title={recovery ? undefined : "Digite os 6 dígitos."}
          aria-describedby="mfa-feedback"
          className="mt-2 block h-12 w-full rounded-lg border border-slate-400 px-3 font-mono text-base focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#01247d]"
        />
      </label>
      <Button type="submit" disabled={pending || disabled} className="min-h-11">{pending ? "Enviando…" : submitLabel}</Button>
    </form>
  );
}

export function MfaFlow({ onComplete, onSessionLost }: { onComplete?: () => void; onSessionLost?: () => void }) {
  const router = useRouter();
  const active = useRef(false);
  const pageVersion = useRef(0);
  const busy = useRef(false);
  const lockedUntil = useRef(0);
  const [screen, setScreen] = useState<Screen>({ kind: "loading" });
  const [attempt, setAttempt] = useState(0);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [deadline, setDeadline] = useState(0);
  const [action, setAction] = useState<Action>();
  const [recovery, setRecovery] = useState(false);

  const reload = useCallback(() => {
    setAction(undefined);
    setRecovery(false);
    setError("");
    setScreen({ kind: "loading" });
    setAttempt((value) => value + 1);
  }, []);

  useEffect(() => {
    active.current = true;
    const controller = new AbortController();
    const stop = onSessionEnd((reason) => {
      if (!active.current) return;
      active.current = false;
      controller.abort();
      setScreen({ kind: "redirecting" });
      if (onSessionLost) onSessionLost();
      else router.replace(reason === "SESSION_REVOKED" ? "/login?motivo=segundo-fator-encerrado" : "/login");
    });
    void getMfaStatus(controller.signal).then(
      (status) => {
        if (controller.signal.aborted) return;
        setScreen({ kind: "ready", status });
      },
      (cause: unknown) => {
        if (controller.signal.aborted) return;
        setScreen({ kind: "unavailable" });
        setError(cause instanceof ApiError ? cause.message : "Não foi possível verificar a segurança da conta.");
      },
    );
    return () => {
      active.current = false;
      controller.abort();
      stop();
    };
  }, [attempt, onSessionLost, router]);

  useEffect(() => {
    function hide() {
      active.current = false;
      pageVersion.current += 1;
      flushSync(() => setScreen({ kind: "loading" }));
    }
    function show(event: PageTransitionEvent) {
      if (event.persisted) {
        busy.current = false;
        setPending(false);
        reload();
      }
    }
    window.addEventListener("pagehide", hide);
    window.addEventListener("pageshow", show);
    return () => {
      window.removeEventListener("pagehide", hide);
      window.removeEventListener("pageshow", show);
    };
  }, [reload]);

  useEffect(() => {
    if (!deadline) return;
    let timer: number;
    function unlock() {
      const remaining = deadline - Date.now();
      if (remaining <= 0) setDeadline(0);
      else timer = window.setTimeout(unlock, Math.min(remaining, 2_147_483_647));
    }
    timer = window.setTimeout(unlock, Math.min(deadline - Date.now(), 2_147_483_647));
    return () => window.clearTimeout(timer);
  }, [deadline]);

  function complete() {
    setScreen({ kind: "redirecting" });
    if (onComplete) onComplete();
    else router.replace("/app");
  }

  async function run<T>(request: () => Promise<T>, apply: (value: T) => void, allowWhileLocked = false) {
    if (busy.current || (!allowWhileLocked && Date.now() < lockedUntil.current)) return;
    const version = pageVersion.current;
    busy.current = true;
    setPending(true);
    setError("");
    try {
      const value = await request();
      if (active.current && version === pageVersion.current) apply(value);
    } catch (cause) {
      if (!active.current || version !== pageVersion.current) return;
      if (isMfaGate(cause)) {
        reload();
        return;
      }
      if (cause instanceof ApiError) {
        if (cause.status === 429 || (cause.status === 503 && cause.retryAfter)) {
          lockedUntil.current = loginRetryDeadline(cause.retryAfter, Date.now());
          setDeadline(lockedUntil.current);
        }
        if (cause.code === "MFA_SETUP_EXPIRED" && screen.kind === "setup") {
          setScreen({ kind: "ready", status: screen.status });
        }
        setError(messages[cause.code] ?? cause.message);
      } else setError("Não foi possível concluir a operação.");
    } finally {
      if (version === pageVersion.current) {
        busy.current = false;
        if (active.current) setPending(false);
      }
    }
  }

  function signOut() {
    void run(logout, () => {
      setScreen({ kind: "redirecting" });
      if (onSessionLost) onSessionLost();
      else router.replace("/login?motivo=saiu");
    }, true);
  }

  function start(status: MfaStatus, code?: string) {
    void run(() => setupMfa(code), (secret) => setScreen({ kind: "setup", secret, status }));
  }

  function manage(status: MfaStatus, selected: Action, code: string) {
    if (selected === "rotate") start(status, code);
    if (selected === "regenerate") {
      void run(() => issueRecoveryCodes("regenerate", code), (codes) => setScreen({ kind: "codes", codes, next: "manage" }));
    }
    if (selected === "disable") {
      void run(() => disableMfa(code), () => {
        setScreen({ kind: "redirecting" });
        router.replace("/login?motivo=fator-alterado");
      });
    }
  }

  function acknowledge(next: "panel" | "login" | "manage") {
    if (next === "panel") complete();
    if (next === "manage") reload();
    if (next === "login") {
      setScreen({ kind: "redirecting" });
      router.replace("/login?motivo=fator-alterado");
    }
  }

  if (screen.kind === "codes") return <RecoveryCodes codes={screen.codes} onContinue={() => acknowledge(screen.next)} />;
  if (screen.kind === "loading" || screen.kind === "redirecting") {
    return <p role="status" className="text-sm text-slate-700">Verificando a sessão…</p>;
  }

  const disabled = deadline > 0;
  const status = screen.kind === "ready" ? screen.status : undefined;
  const actionLabel = action === "rotate" ? "Gerar nova chave" : action === "regenerate" ? "Gerar novos códigos" : "Desativar segundo fator";

  return (
    <section className="space-y-4">
      {screen.kind === "unavailable" && <Button type="button" onClick={reload} className="min-h-11">Tentar novamente</Button>}
      {status?.state === "MFA_PENDING" && (
        <>
          <h2 className="text-xl font-semibold">Confirme o segundo fator</h2>
          <p className="text-sm text-slate-700">Informe o código de 6 dígitos do aplicativo ou use um código de recuperação.</p>
          <CodeForm
            key={recovery ? "recovery" : "totp"} recovery={recovery}
            label={recovery ? "Código de recuperação" : undefined}
            onSubmit={(code) => run(() => verifyMfa(recovery ? { recoveryCode: code } : { code }), complete)}
            pending={pending} disabled={disabled} submitLabel="Verificar código"
          />
          <Button type="button" variant="outline" disabled={pending} onClick={() => { setRecovery(!recovery); setError(""); }} className="min-h-11">
            {recovery ? "Usar aplicativo autenticador" : "Usar código de recuperação"}
          </Button>
        </>
      )}
      {status?.state === "ENROLLMENT_REQUIRED" && (
        <>
          <h2 className="text-xl font-semibold">Configure o segundo fator</h2>
          <p className="text-sm text-slate-700">Adicione o Branly ao seu aplicativo autenticador para proteger sua conta.</p>
          <Button type="button" disabled={pending || disabled} onClick={() => start(status)} className="min-h-11">
            {pending ? "Gerando…" : "Gerar chave de configuração"}
          </Button>
        </>
      )}
      {screen.kind === "setup" && (
        <>
          <h2 className="text-xl font-semibold">Adicione a chave ao autenticador</h2>
          <p className="text-sm text-slate-700">Escolha a opção de inserir uma chave manualmente. Use o tipo baseado em tempo e confirme com um código gerado pela chave nova.</p>
          <p className="select-all break-all rounded-lg border border-slate-200 bg-slate-50 p-3 font-mono text-sm" translate="no">{screen.secret}</p>
          <CodeForm
            onSubmit={(code) => run(() => issueRecoveryCodes("confirm", code), (codes) => setScreen({
              kind: "codes", codes, next: screen.status.enrolled ? "login" : "panel",
            }))}
            pending={pending} disabled={disabled} label="Código da chave nova" submitLabel="Confirmar configuração"
          />
          <Button type="button" variant="outline" disabled={pending} onClick={reload} className="min-h-11">Cancelar configuração</Button>
        </>
      )}
      {status?.state === "COMPLETE" && (
        <>
          <h2 className="text-xl font-semibold">Segundo fator {status.enrolled ? "ativo" : "desativado"}</h2>
          {onComplete ? (
            <Button type="button" onClick={complete} className="min-h-11">Voltar ao convite</Button>
          ) : status.enrolled ? (
            <>
              <p className="text-sm text-slate-700">Códigos de recuperação restantes: {status.recoveryCodesRemaining}.</p>
              {action ? (
                <>
                  <h3 className="font-semibold">{actionLabel}</h3>
                  <p className="text-sm text-slate-700">
                    {action === "regenerate" ? "Os códigos de recuperação anteriores deixarão de funcionar." : "Esta ação vai encerrar todas as suas sessões, incluindo esta."}
                  </p>
                  <CodeForm key={action} onSubmit={(code) => manage(status, action, code)} pending={pending} disabled={disabled} submitLabel={actionLabel} />
                  <Button type="button" variant="outline" disabled={pending} onClick={() => { setAction(undefined); setError(""); }} className="min-h-11">Cancelar</Button>
                </>
              ) : (
                <div className="flex flex-wrap gap-2">
                  <Button type="button" variant="outline" onClick={() => setAction("rotate")} className="min-h-11">Trocar autenticador</Button>
                  <Button type="button" variant="outline" onClick={() => setAction("regenerate")} className="min-h-11">Gerar códigos de recuperação</Button>
                  {!status.required && <Button type="button" variant="outline" onClick={() => setAction("disable")} className="min-h-11">Desativar segundo fator</Button>}
                </div>
              )}
            </>
          ) : (
            <Button type="button" disabled={pending || disabled} onClick={() => start(status)} className="min-h-11">Configurar segundo fator</Button>
          )}
        </>
      )}
      <div id="mfa-feedback" role="alert" className="text-sm text-red-900">
        {error && <p>{error}</p>}
        {disabled && <p>Você pode tentar novamente em {new Date(deadline).toLocaleString("pt-BR", { dateStyle: "short", timeStyle: "medium" })}.</p>}
      </div>
      {error === messages.MFA_ALREADY_COMPLETE || error === messages.MFA_MANDATORY || error === messages.MFA_NOT_ENROLLED ? (
        <Button type="button" variant="outline" onClick={reload} className="min-h-11">Atualizar segurança da conta</Button>
      ) : null}
      <div className="flex flex-wrap gap-3">
        {status?.state === "COMPLETE" && !onComplete && !action && (
          <Link href="/app" className="inline-flex min-h-11 items-center rounded-md px-2 text-sm font-semibold underline underline-offset-4 focus-visible:outline-2">Voltar ao painel</Link>
        )}
        {screen.kind !== "unavailable" && <Button type="button" variant="ghost" onClick={signOut} disabled={pending} className="min-h-11">Sair</Button>}
      </div>
    </section>
  );
}
