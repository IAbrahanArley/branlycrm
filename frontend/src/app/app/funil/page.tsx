import type { Metadata } from "next";
import { FunnelWorkspace } from "@/components/funnel/funnel-workspace";

export const metadata: Metadata = { title: "Funil de vendas | Branly CRM" };

export default function FunnelPage() {
  return <FunnelWorkspace />;
}
