import type { Metadata } from "next";
import { AgencyWorkspace } from "@/components/agency/agency-workspace";

export const metadata: Metadata = { title: "Carteira da agência | Branly CRM" };

export default function AgencyPage() {
  return <AgencyWorkspace />;
}
