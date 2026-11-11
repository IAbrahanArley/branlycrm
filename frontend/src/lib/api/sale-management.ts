import { ApiError, apiRequest } from "@/lib/api/client";

export const paymentLabels = {
  PIX: "Pix", CASH: "Dinheiro", CREDIT_CARD: "Cartão de crédito", DEBIT_CARD: "Cartão de débito",
  BANK_SLIP: "Boleto", TRANSFER: "Transferência", OTHER: "Outro",
} as const;
export type PaymentMethod = keyof typeof paymentLabels;
export type SaleStatus = "ACTIVE" | "CANCELLED";
type SaleValues = {
  procedureId: string; professionalId: string | null; amountCents: number; soldOn: string; paymentMethod: PaymentMethod | null;
};
export type Sale = SaleValues & {
  id: string; leadId: string; contactId: string; status: SaleStatus; version: number; cancelReason: string | null;
};
export type SaleEvent = {
  id: string; actorId: string; occurredAt: string; type: "CREATED" | "CORRECTED" | "CANCELLED"; reason: string | null;
  procedureId: string | null; professionalId: string | null; amountCents: number | null; soldOn: string | null; paymentMethod: PaymentMethod | null;
  previousProcedureId: string | null; previousProfessionalId: string | null; previousAmountCents: number | null;
  previousSoldOn: string | null; previousPaymentMethod: PaymentMethod | null;
};
export type SaleFilters = { status: SaleStatus | ""; from: string; to: string; page: number };
type SaleSummary = Pick<Sale, "id" | "procedureId" | "amountCents" | "soldOn" | "status">;
type SalePage = { items: SaleSummary[]; total: number; page: number; pageSize: number };
type CatalogOption = { id: string; name: string; active: boolean };
export type SaleCorrection = SaleValues & { reason: string };
const base = "/v1/clinic/sales";
const headers = { "Content-Type": "application/json" };

export async function listSales(filters: SaleFilters, signal: AbortSignal): Promise<SalePage> {
  const query = new URLSearchParams({ page: String(filters.page), pageSize: "20" });
  if (filters.status) query.set("status", filters.status);
  if (filters.from) query.set("from", filters.from);
  if (filters.to) query.set("to", filters.to);
  const { items, page, pageSize, total } = await apiRequest<SalePage>(`${base}?${query}`, { signal });
  return { page, pageSize, total, items: items.map(({ id, procedureId, amountCents, soldOn, status }) => ({ id, procedureId, amountCents, soldOn, status })) };
}

export function getSale(id: string, signal: AbortSignal) {
  return apiRequest<Sale>(`${base}/${id}`, { signal });
}

export function getSaleHistory(id: string, signal: AbortSignal) {
  return apiRequest<SaleEvent[]>(`${base}/${id}/events`, { signal });
}

export function getSaleProcedures(signal: AbortSignal) {
  return apiRequest<CatalogOption[]>("/v1/clinic/procedures?includeInactive=true", { signal });
}

export async function getSaleCatalog(signal: AbortSignal) {
  const [procedures, professionals] = await Promise.all([
    getSaleProcedures(signal),
    apiRequest<CatalogOption[]>("/v1/clinic/professionals?includeInactive=true", { signal }),
  ]);
  return { procedures, professionals };
}

async function writeSale(sale: Sale, change: SaleCorrection | { reason: string }) {
  const correction = "procedureId" in change;
  let saved: Sale | null;
  try {
    saved = await apiRequest<Sale | null>(correction ? `${base}/${sale.id}` : `${base}/${sale.id}/cancellation`, {
      method: correction ? "PUT" : "POST", headers, body: JSON.stringify({ ...change, version: sale.version }),
    });
  } catch (error) {
    if (!(error instanceof ApiError)) throw error;
    const messages: Record<string, string> = {
      SALE_STALE: "Outra pessoa alterou esta venda. Recarregue os dados antes de continuar.",
      SALE_CANCELLED: "Esta venda já foi cancelada. Recarregue os dados para conferir.",
      SALE_PROCEDURE_UNAVAILABLE: "O procedimento selecionado não está mais disponível. Recarregue os dados.",
      SALE_PROFESSIONAL_UNAVAILABLE: "O profissional selecionado não está mais disponível. Recarregue os dados.",
    };
    if (!Object.hasOwn(messages, error.code)) throw error;
    throw new ApiError(error.status, error.code, messages[error.code], error.fields, error.retryAfter);
  }
  if (!saved || saved.id !== sale.id || saved.leadId !== sale.leadId || saved.contactId !== sale.contactId ||
    !Number.isSafeInteger(saved.version) || saved.version <= sale.version ||
    (correction
      ? saved.status !== "ACTIVE" || saved.procedureId !== change.procedureId || saved.professionalId !== change.professionalId ||
        saved.amountCents !== change.amountCents || saved.soldOn !== change.soldOn || saved.paymentMethod !== change.paymentMethod
      : saved.status !== "CANCELLED" || saved.cancelReason !== change.reason)) {
    throw new ApiError(200, "UNEXPECTED_RESPONSE", "A API não confirmou a alteração da venda.");
  }
}

export function correctSale(sale: Sale, correction: SaleCorrection) {
  return writeSale(sale, correction);
}

export function cancelSale(sale: Sale, reason: string) {
  return writeSale(sale, { reason });
}
