import type { LeadPage, LeadSummary } from "./leads.ts";

export function previewStageMove(page: LeadPage, lead: LeadSummary, source: string, target: string, stageId: string) {
  if (source === target || (stageId !== source && stageId !== target)) return page;
  const items = page.items.filter((item) => item.id !== lead.id);
  const belongsToTarget = stageId === target;
  if (belongsToTarget && page.page === 1) items.unshift({ ...lead, stageId: target });
  return { ...page, items: items.slice(0, page.pageSize), total: page.total + (belongsToTarget ? 1 : -1) };
}

const errorMessages: Record<string, string> = {
  LEAD_STALE: "Outra pessoa alterou este lead. Atualize o funil antes de tentar novamente.",
  LEAD_CLOSED: "Este lead já foi encerrado. Atualize o funil para conferir a situação.",
  LEAD_STAGE_NOT_ALLOWED: "Esta etapa não está disponível para o movimento. Atualize o funil.",
  LEAD_LOSS_REASON_NOT_FOUND: "Este motivo de perda não está disponível. Atualize o funil.",
  LEAD_NOT_FOUND: "Este lead não está disponível nesta clínica.",
};

export function funnelErrorMessage(code: string, fallback: string) {
  return Object.hasOwn(errorMessages, code) ? errorMessages[code] : fallback;
}
