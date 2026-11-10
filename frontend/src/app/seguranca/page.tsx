import type { Metadata } from "next";
import { MfaFlow } from "@/components/auth/mfa-flow";

export const metadata: Metadata = {
  title: "Segurança da conta | Branly CRM",
  referrer: "no-referrer",
  robots: { index: false, follow: false },
};

export default function SecurityPage() {
  return (
    <main className="grid min-h-dvh place-items-center bg-[#f4f6f6] px-4 py-8 text-[#011127] sm:py-14">
      <div className="w-full max-w-lg space-y-5 rounded-2xl border border-slate-200 border-t-4 border-t-[#01247d] bg-white px-5 py-7 shadow-sm sm:p-9">
        <p className="font-[family-name:var(--font-branly)] text-lg font-extrabold" translate="no">Branly CRM</p>
        <h1 className="text-3xl font-extrabold tracking-tight">Segurança da conta</h1>
        <MfaFlow />
      </div>
    </main>
  );
}
