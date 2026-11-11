import type { Metadata } from "next";
import { CatalogWorkspace } from "@/components/catalog/catalog-workspace";

export const metadata: Metadata = { title: "Catálogo da clínica | Branly CRM" };

export default function CatalogPage() {
  return <CatalogWorkspace />;
}
