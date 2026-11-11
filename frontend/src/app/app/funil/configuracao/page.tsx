import type { Metadata } from "next";
import { PipelineConfigPage } from "@/components/funnel/pipeline-config";

export const metadata: Metadata = { title: "Configuração do funil | Branly CRM" };

export default function Page() {
  return <PipelineConfigPage />;
}
