import type { Metadata } from "next";
import { ClinicWhatsapp } from "@/components/whatsapp/channel-card";

export const metadata: Metadata = { title: "WhatsApp | Branly CRM" };

export default function WhatsappPage() {
  return (
    <>
      <header className="space-y-2">
        <h1 className="font-[family-name:var(--font-branly)] text-3xl font-extrabold tracking-tight sm:text-4xl">WhatsApp</h1>
        <p className="text-sm text-muted-foreground">Gerencie a conexão do número da clínica.</p>
      </header>
      <ClinicWhatsapp />
    </>
  );
}
