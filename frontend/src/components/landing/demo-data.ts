export type Stage = "new" | "evaluation" | "won";
export type DemoModule =
  | "pipeline"
  | "leads"
  | "relationship"
  | "automation"
  | "insights";
export type DemoOpportunity = {
  id: string;
  name: string;
  initials: string;
  interest: string;
  origin: string;
  amountCents: number;
  stage: Stage;
};
export const stages: { id: Stage; label: string }[] = [
  { id: "new", label: "Novo contato" },
  { id: "evaluation", label: "Em avaliação" },
  { id: "won", label: "Concluído" },
];
export const initialOpportunities: DemoOpportunity[] = [
  {
    id: "demo-a",
    name: "Marina A.",
    initials: "MA",
    interest: "Avaliação facial",
    origin: "Instagram",
    amountCents: 65000,
    stage: "new",
  },
  {
    id: "demo-b",
    name: "Camila B.",
    initials: "CB",
    interest: "Avaliação corporal",
    origin: "Indicação",
    amountCents: 90000,
    stage: "evaluation",
  },
  {
    id: "demo-c",
    name: "Luiza C.",
    initials: "LC",
    interest: "Avaliação estética",
    origin: "Site",
    amountCents: 48000,
    stage: "new",
  },
  {
    id: "demo-d",
    name: "Renata D.",
    initials: "RD",
    interest: "Avaliação facial",
    origin: "Instagram",
    amountCents: 72000,
    stage: "won",
  },
];
export const money = (cents: number) =>
  new Intl.NumberFormat("pt-BR", {
    style: "currency",
    currency: "BRL",
    maximumFractionDigits: 0,
  }).format(cents / 100);
