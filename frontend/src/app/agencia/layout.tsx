import type { ReactNode } from "react";
import { PanelShell } from "@/components/panel/panel-shell";

export default function AgencyLayout({ children }: { children: ReactNode }) {
  return <PanelShell>{children}</PanelShell>;
}
