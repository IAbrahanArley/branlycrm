import type { Metadata } from "next";
import { LegalPage } from "@/components/legal/legal-page";
import { DELETION } from "@/components/legal/deletion";

export const metadata: Metadata = {
  title: `${DELETION.title} | Branly CRM`,
  description: DELETION.description,
};

export default function DeletionPage() {
  return <LegalPage document={DELETION} current="/exclusao-de-dados" />;
}
