import type { Metadata } from "next";
import { LeadDetailPage } from "@/components/funnel/lead-detail";

export const metadata: Metadata = { title: "Detalhe do lead | Branly CRM" };

export default async function LeadPage({ params }: { params: Promise<{ leadId: string }> }) {
  return <LeadDetailPage leadId={(await params).leadId} />;
}
