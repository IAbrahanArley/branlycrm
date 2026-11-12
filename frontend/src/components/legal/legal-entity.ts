/**
 * Who answers for the legal pages (docs/legal/README.md). The texts still need legal review
 * before the pilot (CRM-073).
 */
export const LEGAL_ENTITY = {
  product: "Branly CRM",
  company: "AbeSolutiom",
  cnpj: "57.761.114/0001-71",
  address: "Rua Ivanildo Guedes Pessoa, 184, João Pessoa (PB), CEP 58037-325",
  privacyEmail: "contato@amyrepsi.com.br",
  supportEmail: "contato@amyrepsi.com.br",
  dataOfficer: "Abrahan Arley de Almeida Bezerra",
  forum: "Comarca de João Pessoa (PB)",
  retentionDays: "90",
  updatedAt: "12 de novembro de 2026",
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
