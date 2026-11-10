import type { Metadata } from "next";
import { ContactProfile } from "@/components/contacts/contact-profile";

export const metadata: Metadata = { title: "Perfil do contato | Branly CRM" };

export default async function ContactPage({ params }: { params: Promise<{ contactId: string }> }) {
  return <ContactProfile id={(await params).contactId} />;
}
