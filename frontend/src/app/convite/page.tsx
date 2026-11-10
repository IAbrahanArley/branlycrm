import type { Metadata } from "next";
import { InvitationFlow } from "@/components/auth/invitation-flow";

export const metadata: Metadata = {
  title: "Aceitar convite | Branly CRM",
  referrer: "no-referrer",
  robots: { index: false, follow: false },
};

export default function InvitationPage() {
  return (
    <main className="grid min-h-dvh place-items-center bg-[#f4f6f6] px-4 py-8 text-[#011127] sm:py-14">
      <div className="w-full max-w-md rounded-2xl border border-slate-200 border-t-4 border-t-[#01247d] bg-white px-5 py-7 shadow-sm sm:p-9">
        <p className="font-[family-name:var(--font-branly)] text-lg font-extrabold tracking-tight" translate="no">Branly CRM</p>
        <h1 className="mt-5 font-[family-name:var(--font-branly)] text-3xl font-extrabold tracking-tight">Aceitar convite</h1>
        <InvitationFlow />
      </div>
    </main>
  );
}
