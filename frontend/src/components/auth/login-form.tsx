"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { login } from "@/lib/api/auth";
import { ApiError } from "@/lib/api/client";
import { loginRetryDeadline } from "@/lib/api/retry-after";

const inputClass =
  "h-12 w-full rounded-lg border border-slate-400 bg-white px-3 text-base text-slate-950 " +
  "focus-visible:border-[#01247d] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#01247d] " +
  "aria-invalid:border-red-700";

type FormError = {
  message: string;
  credentials?: boolean;
};

export function LoginForm({ onAuthenticated }: { onAuthenticated?: () => void }) {
  const router = useRouter();
  const submitting = useRef(false);
  const retryAtRef = useRef(0);
  const password = useRef<HTMLInputElement>(null);
  const [showPassword, setShowPassword] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<FormError>();
  const [retryAt, setRetryAt] = useState(0);
  const [seconds, setSeconds] = useState(0);

  useEffect(() => {
    if (!retryAt) return;

    const timer = window.setInterval(() => {
      const remaining = Math.max(0, Math.ceil((retryAt - Date.now()) / 1000));
      setSeconds(remaining);
      if (remaining === 0) window.clearInterval(timer);
    }, 250);

    return () => window.clearInterval(timer);
  }, [retryAt]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting.current || Date.now() < retryAtRef.current) return;

    const form = new FormData(event.currentTarget);
    submitting.current = true;
    setPending(true);
    setError(undefined);
    setRetryAt(0);
    setSeconds(0);

    try {
      await login(String(form.get("email") ?? ""), String(form.get("password") ?? ""));
      if (password.current) password.current.value = "";
      if (onAuthenticated) onAuthenticated();
      else router.replace("/app");
    } catch (cause) {
      submitting.current = false;
      setPending(false);
      if (cause instanceof ApiError) {
        if (cause.code === "INVALID_CREDENTIALS") {
          setError({ message: "E-mail ou senha incorretos. Confira os dados e tente novamente.", credentials: true });
          password.current?.focus();
          return;
        }
        if (cause.status === 429 || (cause.status === 503 && cause.retryAfter)) {
          const now = Date.now();
          const deadline = loginRetryDeadline(cause.retryAfter, now);
          retryAtRef.current = deadline;
          setRetryAt(deadline);
          setSeconds(Math.ceil((deadline - now) / 1000));
        }
        setError({ message: cause.message });
        return;
      }
      setError({ message: "Não foi possível entrar. Tente novamente." });
    }
  }

  const waiting = seconds > 0;

  return (
    <form method="post" onSubmit={submit} className="mt-8 space-y-5">
      <div>
        <label htmlFor="email" className="mb-2 block text-sm font-semibold">E-mail</label>
        <input
          id="email"
          name="email"
          type="email"
          autoComplete="username"
          spellCheck={false}
          autoCapitalize="none"
          required
          readOnly={pending}
          aria-invalid={error?.credentials}
          aria-describedby={error?.credentials ? "login-error" : undefined}
          className={inputClass}
        />
      </div>
      <div>
        <label htmlFor="password" className="mb-2 block text-sm font-semibold">Senha</label>
        <div className="relative">
          <input
            ref={password}
            id="password"
            name="password"
            type={showPassword ? "text" : "password"}
            autoComplete="current-password"
            spellCheck={false}
            autoCapitalize="none"
            required
            readOnly={pending}
            aria-invalid={error?.credentials}
            aria-describedby={error?.credentials ? "login-error" : undefined}
            className={`${inputClass} pr-28`}
          />
          <button
            type="button"
            aria-controls="password"
            onClick={() => setShowPassword((visible) => !visible)}
            className="absolute inset-y-0 right-1 my-0.5 min-w-24 touch-manipulation rounded-md px-2 text-sm font-semibold text-[#01247d] hover:bg-slate-100 focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-[#01247d]"
          >
            {showPassword ? "Ocultar senha" : "Mostrar senha"}
          </button>
        </div>
      </div>
      <div role="alert" id="login-error">
        {error && (
          <p className="rounded-lg border border-red-200 bg-red-50 px-3 py-2.5 text-sm leading-relaxed text-red-900">
            {error.message}
          </p>
        )}
      </div>
      <div>
        <Button
          type="submit"
          disabled={pending || waiting}
          className="min-h-12 w-full touch-manipulation bg-[#01247d] px-4 font-semibold text-white transition-colors hover:bg-[#011b60] focus-visible:ring-[#01247d] disabled:opacity-75"
        >
          {pending ? "Entrando…" : "Entrar"}
        </Button>
        <p role="status" className={retryAt ? "mt-2 text-sm text-slate-700" : "sr-only"}>
          {pending ? "Verificando suas credenciais." : retryAt ? (
            waiting ? (
              <>
                <span className="sr-only">Aguarde para tentar novamente.</span>
                <span aria-hidden="true">Tente novamente em {seconds} s.</span>
              </>
            ) : "Você já pode tentar novamente."
          ) : ""}
        </p>
      </div>
    </form>
  );
}
