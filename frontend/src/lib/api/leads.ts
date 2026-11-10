import { ApiError, apiRequest } from "@/lib/api/client";

export const temperatures = ["COLD", "WARM", "HOT", "QUALIFIED"] as const;
export type Temperature = (typeof temperatures)[number];
export type LeadStatus = "OPEN" | "WON" | "LOST";
export type Stage = { id: string; name: string; key: string | null; kind: LeadStatus; position: number | null };
export type Pipeline = { id: string; name: string; stages: Stage[]; version: number };
export type Lead = {
  id: string; contactId: string; stageId: string; status: LeadStatus; temperature: Temperature;
  responsibleId: string | null; openedAt: string; version: number;
};
export type LeadSummary = Lead & { contactName: string; contactPhone: string };
export type LeadDetail = Lead & { closedAt: string | null; lossReasonId: string | null };
export type LossReason = { id: string; name: string; archived: boolean };
export type LeadPage = { items: LeadSummary[]; total: number; page: number; pageSize: number };
export type LeadFilters = { status: LeadStatus; stageId?: string; temperature?: Temperature | ""; page: number };

const base = "/v1/clinic/leads";
const headers = { "Content-Type": "application/json" };

export function getPipeline(signal: AbortSignal) {
  return apiRequest<Pipeline>("/v1/clinic/pipeline", { signal });
}

export function listLeads({ status, stageId, temperature, page }: LeadFilters, signal: AbortSignal) {
  const params = new URLSearchParams({ status, page: String(page), pageSize: "20" });
  if (stageId) params.set("stageId", stageId);
  if (temperature) params.set("temperature", temperature);
  return apiRequest<LeadPage>(`${base}?${params}`, { signal });
}

export function getLead(id: string, signal: AbortSignal) {
  return apiRequest<LeadDetail>(`${base}/${id}`, { signal });
}

export function getLossReasons(signal: AbortSignal, includeArchived = false) {
  return apiRequest<LossReason[]>(`/v1/clinic/loss-reasons?includeArchived=${includeArchived}`, { signal });
}

export async function moveLead(lead: Lead, stageId: string) {
  const result = await apiRequest<LeadDetail | null>(`${base}/${lead.id}/stage`, {
    method: "PUT", headers, body: JSON.stringify({ stageId, version: lead.version }),
  });
  if (result?.id !== lead.id || result.stageId !== stageId || result.status !== "OPEN" || !(result.version > lead.version)) {
    throw new ApiError(200, "UNEXPECTED_RESPONSE", "A API não confirmou a etapa do lead.");
  }
}

export async function loseLead(lead: Lead, lossReasonId: string) {
  const result = await apiRequest<LeadDetail | null>(`${base}/${lead.id}/loss`, {
    method: "POST", headers, body: JSON.stringify({ lossReasonId, version: lead.version }),
  });
  if (result?.id !== lead.id || result.lossReasonId !== lossReasonId || result.status !== "LOST" || !(result.version > lead.version)) {
    throw new ApiError(200, "UNEXPECTED_RESPONSE", "A API não confirmou o encerramento do lead.");
  }
}
