import type { Sale, SaleCorrection } from "./api/sale-management.ts";

export function saleValuesChanged(sale: Sale, correction: SaleCorrection) {
  return sale.procedureId !== correction.procedureId ||
    sale.professionalId !== correction.professionalId ||
    sale.amountCents !== correction.amountCents ||
    sale.soldOn !== correction.soldOn ||
    sale.paymentMethod !== correction.paymentMethod;
}
