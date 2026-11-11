import type { Metadata } from "next";
import { SalesWorkspace } from "@/components/sales/sales-workspace";

export const metadata: Metadata = { title: "Vendas | Branly CRM" };

export default function SalesPage() {
  return <SalesWorkspace />;
}
