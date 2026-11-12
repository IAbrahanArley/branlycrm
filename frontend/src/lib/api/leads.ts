import { ApiError, apiRequest } from "@/lib/api/client";
import { isContactId } from "@/lib/api/contacts";
import { isOriginType, originSources, type LeadOrigin, type OriginDraft } from "@/lib/lead-origin";

export const temperatures = ["COLD", "WARM", "HOT", "QUALIFIED"] as const;
export type Temperature = (typeof temperatures)[number];
export type LeadStatus = "OPEN" | "WON" | "LOST";
export type Stage = { id: string; name: string; key: string | null; kind: LeadStatus; position: number | null };
export type Pipeline = { id: string; name: string; stages: Stage[]; archivedStages: Stage[]; version: number };
export type Lead = {
  id: string; contactId: string; stageId: string; status: LeadStatus; temperature: Temperature | null;
  responsibleId: string | null; openedAt: string; version: number;
};
export type LeadSummary = Lead & { contactName: string; contactPhone: string };
export type LeadDetail = Lead & { closedAt: string | null; lossReasonId: string | null; origin: LeadOrigin };
type OriginTouch = { id: string; leadId: string | null; receivedAt: string; origin: LeadOrigin };
export type LeadEvent = {
  id: string;
  type: string;
  fromValue: string | null;
  toValue: string | null;
  lossReasonId: string | null;
  actorId: string | null;
  occurredAt: string;
};
export type LeadNote = { id: string; authorId: string; body: string; createdAt: string };
export type LossReason = { id: string; name: string; archived: boolean; version: number };
export type LeadPage = { items: LeadSummary[]; total: number; page: number; pageSize: number };
export type LeadFilters = { status?: LeadStatus; stageId?: string; temperature?: Temperature | ""; contactId?: string; page: number };

const base = "/v1/clinic/leads";
const headers = { "Content-Type": "application/json" };

export function getPipeline(signal: AbortSignal) {
  return apiRequest<Pipeline>("/v1/clinic/pipeline", { signal });
}

export function listLeads({ status, stageId, temperature, contactId, page }: LeadFilters, signal: AbortSignal) {
  const params = new URLSearchParams({ page: String(page), pageSize: "20" });
  if (status) params.set("status", status);
  if (stageId) params.set("stageId", stageId);
  if (temperature) params.set("temperature", temperature);
  if (contactId) params.set("contactId", contactId);
  return apiRequest<LeadPage>(`${base}?${params}`, { signal });
}

export function getLead(id: string, signal: AbortSignal) {
  return apiRequest<LeadDetail>(`${base}/${id}`, { signal });
}

function isOrigin(origin: LeadOrigin | null | undefined): origin is LeadOrigin {
  return Boolean(origin && isOriginType(origin.type) && Object.hasOwn(originSources, origin.source)
    && (origin.code === null || typeof origin.code === "string")
    && (origin.title === null || typeof origin.title === "string"));
}

function matchesOrigin(value: LeadOrigin | null | undefined, draft?: OriginDraft) {
  if (!isOrigin(value)) return false;
  return draft
    ? value.source === "MANUAL" && value.type === draft.type && value.code === draft.code && value.title === draft.title
    : value.source === "NONE" && value.type === "UNIDENTIFIED" && value.code === null && value.title === null;
}

export async function openLead(contactId: string, origin?: OriginDraft, temperature?: Temperature) {
  const result = await apiRequest<LeadDetail | null>(base, {
    method: "POST", headers, body: JSON.stringify({ contactId, origin, temperature }),
  });
  if (!result || !isContactId(result.id) || result.contactId !== contactId || result.status !== "OPEN" || !matchesOrigin(result.origin, origin)) {
    throw new ApiError(201, "UNEXPECTED_RESPONSE", "A API não confirmou a criação do lead.");
  }
  return result;
}

export async function correctLeadOrigin(lead: LeadDetail, draft: OriginDraft) {
  const result = await apiRequest<LeadDetail | null>(`${base}/${lead.id}/origin`, {
    method: "PUT", headers, body: JSON.stringify({ ...draft, version: lead.version }),
  });
  if (result?.id !== lead.id || result.contactId !== lead.contactId || !Number.isSafeInteger(result.version)
      || result.version < lead.version || !matchesOrigin(result.origin, draft)) {
    throw new ApiError(200, "UNEXPECTED_RESPONSE", "A API não confirmou a correção da origem.");
  }
  return result;
}

export async function listOriginTouches(contactId: string, signal: AbortSignal) {
  const result = await apiRequest<OriginTouch[]>(`/v1/clinic/contacts/${contactId}/origin-touches`, { signal });
  if (!Array.isArray(result) || !result.every((touch) => touch && isContactId(touch.id)
    && (touch.leadId === null || isContactId(touch.leadId)) && Number.isFinite(Date.parse(touch.receivedAt)) && isOrigin(touch.origin))) {
    throw new ApiError(200, "UNEXPECTED_RESPONSE", "A API não retornou um histórico de chegadas válido.");
  }
  return result;
}

export function listLeadEvents(id: string, signal: AbortSignal) {
  return apiRequest<LeadEvent[]>(`${base}/${id}/events`, { signal });
}

export function listLeadNotes(id: string, signal: AbortSignal) {
  return apiRequest<LeadNote[]>(`${base}/${id}/notes`, { signal });
}

export async function addLeadNote(id: string, body: string, subjectId: string) {
  const note = await apiRequest<LeadNote | null>(`${base}/${id}/notes`, {
    method: "POST", headers, body: JSON.stringify({ body }),
  });
  // O contrato devolve o texto após String.strip() no servidor, com o conjunto de espaços do Java.
  const edgeWhitespace = "[\\t-\\r\\u001C-\\u0020\\u1680\\u2000-\\u2006\\u2008-\\u200A\\u2028\\u2029\\u205F\\u3000]";
  const expectedBody = body.replace(new RegExp(`^${edgeWhitespace}+|${edgeWhitespace}+$`, "gu"), "");
  if (typeof note?.id !== "string" || !note.id || note.authorId !== subjectId
      || !expectedBody || note.body !== expectedBody || !Number.isFinite(Date.parse(note.createdAt))) {
    throw new ApiError(201, "UNEXPECTED_RESPONSE", "A API não confirmou a nota.");
  }
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
