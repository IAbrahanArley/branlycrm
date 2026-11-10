import type { Metadata } from "next";
import { ContactForm } from "@/components/contacts/contact-form";

export const metadata: Metadata = { title: "Novo contato | Branly CRM" };

export default function NewContactPage() {
  return <ContactForm />;
}
