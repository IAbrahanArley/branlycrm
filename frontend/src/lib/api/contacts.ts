import { apiRequest } from "@/lib/api/client";

export { withSelectedClinic as withContactClinic } from "@/lib/api/auth";

export type ContactSummary = {
  id: string;
  name: string;
  phone: string;
  email: string | null;
  createdVia: string;
  archivedAt: string | null;
};
export type Tag = { id: string; name: string };
export type ConsentChannel = "WHATSAPP" | "EMAIL";
export type ConsentStatus = "OPT_IN" | "OPT_OUT";
type ConsentState = ConsentStatus | "NONE";
export type ConsentHistory = {
  current: Record<ConsentChannel, ConsentState>;
  history: {
    id: string; channel: ConsentChannel; status: ConsentStatus;
    source: string; recordedAt: string; recordedBy: string | null;
  }[];
};
export type Contact = ContactSummary & {
  birthDate: string | null;
  notes: string | null;
  createdAt: string;
  version: number;
  tags: Tag[];
};
export type ContactDraft = { name: string; phone: string; email: string; birthDate: string; notes: string };
export type ContactFilters = { query: string; tagId: string; includeArchived: boolean; page: number };
type ContactPage = { items: ContactSummary[]; page: number; pageSize: number; total: number };

const base = "/v1/clinic/contacts";
const json = { "Content-Type": "application/json" };
export const CONTACT_PAGE_SIZE = 25;

export function isContactId(value: unknown): value is string {
  return typeof value === "string" && /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(value);
}

export function contactPath(id: string) {
  return `/app/contatos/${id}` as const;
}

export function listContacts(filters: ContactFilters, signal: AbortSignal) {
  const query = new URLSearchParams({ page: String(filters.page), pageSize: String(CONTACT_PAGE_SIZE) });
  if (filters.query) query.set("q", filters.query);
  if (filters.tagId) query.set("tagId", filters.tagId);
  if (filters.includeArchived) query.set("includeArchived", "true");
  return apiRequest<ContactPage>(`${base}?${query}`, { signal });
}

export function listTags(signal?: AbortSignal) {
  return apiRequest<Tag[]>("/v1/clinic/tags", { signal });
}

export function getContact(id: string, signal?: AbortSignal) {
  return apiRequest<Contact>(`${base}/${id}`, { signal });
}

export function getConsents(id: string, signal?: AbortSignal) {
  return apiRequest<ConsentHistory>(`${base}/${id}/consents`, { signal });
}

export function checkPhone(phone: string, signal: AbortSignal) {
  return apiRequest<{ phone: string; contact: ContactSummary | null }>(`${base}/phone-check`, {
    method: "POST", headers: json, body: JSON.stringify({ phone }), signal,
  });
}

export function saveContact(draft: ContactDraft, previous?: { id: string; version: number }) {
  const body = {
    ...draft,
    birthDate: draft.birthDate || null,
    ...(previous ? { version: previous.version } : {}),
  };
  return apiRequest<Contact>(previous ? `${base}/${previous.id}` : base, {
    method: previous ? "PUT" : "POST", headers: json, body: JSON.stringify(body),
  });
}

export function setArchived(id: string, archived: boolean) {
  return apiRequest<Contact>(`${base}/${id}/archived`, {
    method: "PUT", headers: json, body: JSON.stringify({ archived }),
  });
}

export function setContactTag(id: string, tagId: string, attached: boolean) {
  return apiRequest<Tag[]>(`${base}/${id}/tags/${tagId}`, { method: attached ? "PUT" : "DELETE" });
}

export function createTag(name: string) {
  return apiRequest<Tag>("/v1/clinic/tags", { method: "POST", headers: json, body: JSON.stringify({ name }) });
}

export function saveConsent(id: string, channel: ConsentChannel, status: ConsentStatus) {
  return apiRequest<ConsentHistory>(`${base}/${id}/consents`, {
    method: "POST", headers: json, body: JSON.stringify({ channel, status }),
  });
}
