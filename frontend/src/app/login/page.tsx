import type { Metadata } from "next";
import Link from "next/link";
import { ArrowLeft } from "lucide-react";
import { LoginForm } from "@/components/auth/login-form";
import { sessionEndMessage } from "@/lib/api/client";

export const metadata: Metadata = { title: "Entrar | Branly CRM" };

export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<{ motivo?: string | string[] }>;
}) {
  const { motivo } = await searchParams;
  const notices = {
    "sessao-encerrada": sessionEndMessage("SESSION_REVOKED"),
    "convite-aceito": "Convite aceito. Entre com a conta do convite para continuar.",
    "segundo-fator-encerrado": "A sessão do segundo fator foi encerrada. Entre novamente para continuar.",
    "fator-alterado": "A segurança da conta foi alterada. Entre novamente para continuar.",
    saiu: "Você saiu da sua conta.",
  };
  const notice = typeof motivo === "string" && Object.hasOwn(notices, motivo)
    ? notices[motivo as keyof typeof notices]
    : null;

  return (
    <main className="grid min-h-dvh place-items-center bg-[#f4f6f6] px-4 py-8 text-[#011127] sm:px-8 sm:py-14">
      <div className="w-full max-w-md overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
        <div aria-hidden="true" className="h-1.5 bg-[#01247d]" />
        <div className="px-5 py-7 sm:px-9 sm:py-9">
          <p className="inline-flex items-center gap-2 font-[family-name:var(--font-branly)] text-lg font-extrabold tracking-tight" translate="no">
            <span aria-hidden="true" className="size-2 rounded-full bg-[#00dfe5]" />
            Branly CRM
          </p>
          <h1 className="mt-6 font-[family-name:var(--font-branly)] text-3xl font-extrabold tracking-tight text-balance">
            Entrar no painel
          </h1>
          <p className="mt-2 text-sm leading-relaxed text-slate-700">
            Use seu e-mail e sua senha para acessar a conta.
          </p>
          {notice && (
            <p className="mt-5 rounded-lg border border-blue-200 bg-blue-50 px-3 py-2.5 text-sm text-[#01247d]">
              {notice}
            </p>
          )}
          <LoginForm />
          <div className="mt-6 border-t border-slate-200 pt-3">
            <Link href="/" className="inline-flex min-h-11 touch-manipulation items-center gap-2 rounded-sm text-sm font-semibold text-[#01247d] hover:underline focus-visible:outline-2 focus-visible:outline-offset-4">
              <ArrowLeft aria-hidden="true" size={16} />
              Voltar ao início
            </Link>
          </div>
        </div>
      </div>
    </main>
  );
}
