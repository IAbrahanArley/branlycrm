import type { Metadata } from "next";
import { MonthlyReportWorkspace } from "@/components/reports/monthly-report";

export const metadata: Metadata = { title: "Relatório mensal | Branly CRM" };

export default function ReportsPage() {
  return <MonthlyReportWorkspace />;
}
