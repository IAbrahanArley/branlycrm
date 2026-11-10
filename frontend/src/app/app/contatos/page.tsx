import type { Metadata } from "next";
import { ContactList } from "@/components/contacts/contact-list";

export const metadata: Metadata = { title: "Contatos | Branly CRM" };

export default function ContactsPage() {
  return <ContactList />;
}
