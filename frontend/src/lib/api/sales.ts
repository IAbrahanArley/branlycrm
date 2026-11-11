import { ApiError, apiRequest } from "@/lib/api/client";
import { isContactId as isUuid } from "@/lib/api/contacts";
import type { Lead } from "@/lib/api/leads";

export const paymentMethods = {
  PIX: "Pix",
  CASH: "Dinheiro",
  CREDIT_CARD: "Cartão de crédito",
  DEBIT_CARD: "Cartão de débito",
  BANK_SLIP: "Boleto",
  TRANSFER: "Transferência",
  OTHER: "Outro",
} as const;

type PaymentMethod = keyof typeof paymentMethods;
type Procedure = { id: string; name: string; active: boolean; referencePriceCents: number | null };
type Professional = { id: string; name: string; active: boolean };
type SaleDraft = {
  procedureId: string;
  professionalId?: string;
  amountCents: number;
  soldOn?: string;
  paymentMethod?: PaymentMethod;
};
type RegisteredSale = {
  id: string; leadId: string; contactId: string; procedureId: string; amountCents: number;
  professionalId: string | null; paymentMethod: PaymentMethod | null; soldOn: string; status: "ACTIVE" | "CANCELLED";
};

export async function getSaleOptions(signal: AbortSignal) {
  const [procedures, professionals] = await Promise.all([
    apiRequest<Procedure[]>("/v1/clinic/procedures", { signal }),
    apiRequest<Professional[]>("/v1/clinic/professionals", { signal }),
  ]);
  return {
    procedures: procedures.filter((procedure) => procedure.active),
    professionals: professionals.filter((professional) => professional.active),
  };
}

export async function registerSale(lead: Lead, draft: SaleDraft) {
  const sale = await apiRequest<RegisteredSale | null>(`/v1/clinic/leads/${lead.id}/sale`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ...draft, version: lead.version }),
  });
  if (!isUuid(sale?.id) || sale.leadId !== lead.id || sale.contactId !== lead.contactId ||
    sale.procedureId !== draft.procedureId || sale.amountCents !== draft.amountCents || sale.status !== "ACTIVE" ||
    (sale.professionalId ?? null) !== (draft.professionalId ?? null) ||
    (sale.paymentMethod ?? null) !== (draft.paymentMethod ?? null) ||
    (draft.soldOn !== undefined && sale.soldOn !== draft.soldOn)) {
    throw new ApiError(200, "UNEXPECTED_RESPONSE", "A API não confirmou o registro da venda.");
  }
}
