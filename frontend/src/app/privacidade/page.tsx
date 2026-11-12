import type { Metadata } from "next";
import { LegalPage } from "@/components/legal/legal-page";
import { PRIVACY } from "@/components/legal/privacy";

export const metadata: Metadata = {
  title: `${PRIVACY.title} | Branly CRM`,
  description: PRIVACY.description,
};

export default function PrivacyPage() {
  return <LegalPage document={PRIVACY} current="/privacidade" />;
}
