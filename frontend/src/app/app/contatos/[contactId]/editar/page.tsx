import type { Metadata } from "next";
import { ContactForm } from "@/components/contacts/contact-form";

export const metadata: Metadata = { title: "Editar contato | Branly CRM" };

export default async function EditContactPage({ params }: { params: Promise<{ contactId: string }> }) {
  return <ContactForm id={(await params).contactId} />;
}
