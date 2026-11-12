import type { Metadata } from "next";
import { LegalPage } from "@/components/legal/legal-page";
import { TERMS } from "@/components/legal/terms";

export const metadata: Metadata = {
  title: `${TERMS.title} | Branly CRM`,
  description: TERMS.description,
};

export default function TermsPage() {
  return <LegalPage document={TERMS} current="/termos" />;
}
