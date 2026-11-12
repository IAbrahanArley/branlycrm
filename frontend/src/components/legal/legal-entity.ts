/**
 * Who answers for the legal pages. Every value between brackets is a placeholder that must be
 * replaced, and the texts reviewed (docs/legal/README.md), before the pages are linked from the
 * Meta app or shown to clinics.
 */
export const LEGAL_ENTITY = {
  product: "Branly CRM",
  company: "AbeSolutiom",
  cnpj: "[CNPJ]",
  address: "[endereço completo da empresa]",
  privacyEmail: "[e-mail de privacidade]",
  supportEmail: "[e-mail de suporte]",
  dataOfficer: "[nome do encarregado pelo tratamento de dados]",
  forum: "[comarca do foro]",
  retentionDays: "[prazo em dias]",
  updatedAt: "[data da versão publicada]",
} as const;

export type LegalBlock = string | readonly string[];

export interface LegalSection {
  id: string;
  title: string;
  blocks: readonly LegalBlock[];
}

export interface LegalDocument {
  title: string;
  description: string;
  intro: string;
  sections: readonly LegalSection[];
}
