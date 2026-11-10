import type { Metadata } from "next";
import type { ReactNode } from "react";
import { PanelShell } from "@/components/panel/panel-shell";

export const metadata: Metadata = { title: "Painel | Branly CRM" };

export default function PanelLayout({ children }: { children: ReactNode }) {
  return <PanelShell>{children}</PanelShell>;
}
