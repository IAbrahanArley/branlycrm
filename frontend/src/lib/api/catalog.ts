import { ApiError, apiRequest } from "@/lib/api/client";
import { isContactId as isUuid } from "@/lib/api/contacts";

export type CatalogKind = "procedures" | "professionals";
export type Professional = { id: string; name: string; active: boolean; version: number };
export type Procedure = Professional & { referencePriceCents: number; category: string | null; returnIntervalDays: number | null };
export type CatalogItem = Procedure | Professional;
export type CatalogDraft =
  | { kind: "procedures"; name: string; referencePriceCents: number; category: string | null; returnIntervalDays: number | null }
  | { kind: "professionals"; name: string };

const headers = { "Content-Type": "application/json" };

export function isProcedure(item: CatalogItem): item is Procedure {
  return "referencePriceCents" in item;
}

export function listCatalog(kind: CatalogKind, includeInactive: boolean, signal: AbortSignal) {
  return apiRequest<CatalogItem[]>(`/v1/clinic/${kind}?includeInactive=${includeInactive}`, { signal });
}

export async function saveCatalogItem({ kind, ...draft }: CatalogDraft, current?: CatalogItem) {
  const path = `/v1/clinic/${kind}` as const;
  const saved = await apiRequest<CatalogItem | null>(current ? `${path}/${current.id}` : path, {
    method: current ? "PUT" : "POST", headers, body: JSON.stringify({ ...draft, version: current?.version }),
  });
  if (!isUuid(saved?.id) || saved.name !== draft.name || !Number.isSafeInteger(saved.version) ||
    saved.version < (current?.version ?? 0) || (current && saved.id !== current.id) ||
    (!current && saved.active !== true) ||
    ("referencePriceCents" in draft && (!("referencePriceCents" in saved) || saved.referencePriceCents !== draft.referencePriceCents ||
      saved.category !== draft.category || saved.returnIntervalDays !== draft.returnIntervalDays))) {
    throw new ApiError(200, "UNEXPECTED_RESPONSE", "A API não confirmou os dados do catálogo.");
  }
}

export async function setCatalogActive(kind: CatalogKind, item: CatalogItem, active: boolean) {
  const saved = await apiRequest<CatalogItem | null>(`/v1/clinic/${kind}/${item.id}/active`, {
    method: "PUT", headers, body: JSON.stringify({ active }),
  });
  if (saved?.id !== item.id || saved.active !== active || !Number.isSafeInteger(saved.version) || saved.version < item.version) {
    throw new ApiError(200, "UNEXPECTED_RESPONSE", "A API não confirmou a situação do item.");
  }
}
