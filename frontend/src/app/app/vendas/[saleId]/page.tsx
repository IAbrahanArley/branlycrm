import type { Metadata } from "next";
import { SaleDetailPage } from "@/components/sales/sale-detail";

export const metadata: Metadata = { title: "Detalhe da venda | Branly CRM" };

export default async function SalePage({ params }: { params: Promise<{ saleId: string }> }) {
  return <SaleDetailPage saleId={(await params).saleId} />;
}
