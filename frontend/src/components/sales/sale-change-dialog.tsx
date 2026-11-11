"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { Dialog } from "@base-ui/react/dialog";
import { fieldClass, type useLeadMutation } from "@/components/funnel/funnel-ui";
import { Button } from "@/components/ui/button";
import { cancelSale, correctSale, paymentLabels, type PaymentMethod, type Sale, type SaleCorrection, type getSaleCatalog } from "@/lib/api/sale-management";
import { parseSaleAmount } from "@/lib/sale-amount";
import { saleValuesChanged } from "@/lib/sale-change";

type SaleCatalog = Awaited<ReturnType<typeof getSaleCatalog>>;

export function SaleChangeDialog({ sale, kind, catalog, mutation, onClose, onReload, onSaved }: {
  sale: Sale; kind: "correct" | "cancel"; catalog: SaleCatalog;
  mutation: ReturnType<typeof useLeadMutation>; onClose: () => void; onReload: () => void; onSaved: () => void;
}) {
  const [validation, setValidation] = useState<{ field?: "reason" | "amount"; message: string }>();
  const formRef = useRef<HTMLFormElement>(null);
  const correcting = kind === "correct";
  const locked = mutation.pending || Boolean(mutation.failure?.needsReload);
  const procedures = catalog.procedures.filter((option) => option.active || option.id === sale.procedureId);
  const professionals = catalog.professionals.filter((option) => option.active || option.id === sale.professionalId);
  const reasonRejected = mutation.failure?.fields.includes("reason");

  useEffect(() => {
    if (!mutation.pending && reasonRejected) {
      const input = formRef.current?.elements.namedItem("reason");
      if (input instanceof HTMLElement) input.focus();
    }
  }, [mutation.pending, reasonRejected]);

  function invalid(field: "reason" | "amount", message: string) {
    setValidation({ field, message });
    const input = formRef.current?.elements.namedItem(field);
    if (input instanceof HTMLElement) input.focus();
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (locked || sale.status !== "ACTIVE") return;
    const form = new FormData(event.currentTarget);
    const reason = String(form.get("reason") ?? "").trim();
    if (!reason || /[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/u.test(reason)) {
      invalid("reason", "Informe o motivo da alteração em uma linha.");
      return;
    }
    if (!correcting) {
      setValidation(undefined);
      void mutation.run(() => cancelSale(sale, reason), onSaved);
      return;
    }
    const amountCents = parseSaleAmount(String(form.get("amount") ?? ""));
    if (amountCents === null) {
      invalid("amount", "Informe um valor acima de zero, de até R$ 10 milhões e com até duas casas decimais.");
      return;
    }
    const procedure = procedures.find(({ id }) => id === form.get("procedureId"));
    const professional = professionals.find(({ id }) => id === form.get("professionalId"));
    const soldOn = String(form.get("soldOn") ?? "");
    const payment = String(form.get("paymentMethod") ?? "");
    if (!procedure || !soldOn) return;
    const correction: SaleCorrection = {
      reason, amountCents, soldOn, procedureId: procedure.id, professionalId: professional?.id ?? null,
      paymentMethod: Object.hasOwn(paymentLabels, payment) ? payment as PaymentMethod : null,
    };
    if (!saleValuesChanged(sale, correction)) {
      setValidation({ message: "Altere ao menos um dado da venda antes de confirmar a correção." });
      return;
    }
    setValidation(undefined);
    void mutation.run(() => correctSale(sale, correction), onSaved);
  }

  function close() {
    if (mutation.pending) return;
    if (mutation.failure?.needsReload) onReload();
    else onClose();
  }

  return (
    <Dialog.Root open onOpenChange={(open) => { if (!open) close(); }}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-slate-950/55" />
        <Dialog.Popup className="fixed top-1/2 left-1/2 z-50 max-h-[90dvh] w-[calc(100%-2rem)] max-w-xl -translate-x-1/2 -translate-y-1/2 space-y-4 overflow-y-auto overscroll-contain rounded-xl border bg-card p-5 shadow-xl sm:p-6">
          <Dialog.Title className="text-xl font-bold">{correcting ? "Corrigir venda" : "Cancelar venda"}</Dialog.Title>
          <Dialog.Description className="text-sm text-slate-600">
            {correcting ? "Os valores anteriores e o motivo ficam no histórico." : "A venda fica registrada como cancelada. O lead permanece em Venda fechada."}
          </Dialog.Description>
          <form ref={formRef} method="post" onSubmit={submit} className="space-y-4">
            <fieldset disabled={locked} className="grid gap-4 sm:grid-cols-2">
              <legend className="sr-only">Dados da alteração</legend>
              {correcting && <>
                <label className="text-sm font-semibold">Procedimento
                  <select className={fieldClass} name="procedureId" defaultValue={sale.procedureId} required>
                    <option value="">Selecione um procedimento</option>
                    {procedures.map(({ id, name, active }) => <option key={id} value={id}>{name}{active ? "" : " (inativo)"}</option>)}
                  </select>
                </label>
                <label className="text-sm font-semibold">Valor da venda (R$)
                  <input className={fieldClass} name="amount" inputMode="decimal" autoComplete="off" required maxLength={17}
                    defaultValue={(sale.amountCents / 100).toFixed(2).replace(".", ",")}
                    aria-invalid={validation?.field === "amount"} aria-describedby={validation?.field === "amount" ? "sale-change-validation" : undefined} />
                </label>
                <label className="text-sm font-semibold">Data da venda
                  <input className={fieldClass} name="soldOn" type="date" defaultValue={sale.soldOn} min="2000-01-01" required />
                </label>
                <label className="text-sm font-semibold">Profissional (opcional)
                  <select className={fieldClass} name="professionalId" defaultValue={sale.professionalId ?? ""}>
                    <option value="">Não informado</option>
                    {professionals.map(({ id, name, active }) => <option key={id} value={id}>{name}{active ? "" : " (inativo)"}</option>)}
                  </select>
                </label>
                <label className="text-sm font-semibold sm:col-span-2">Forma de pagamento (opcional)
                  <select className={fieldClass} name="paymentMethod" defaultValue={sale.paymentMethod ?? ""}>
                    <option value="">Não informada</option>
                    {Object.entries(paymentLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
                  </select>
                </label>
              </>}
              <label className="text-sm font-semibold sm:col-span-2">{correcting ? "Motivo da correção" : "Motivo do cancelamento"}
                <input className={fieldClass} name="reason" required maxLength={500} autoComplete="off"
                  aria-invalid={validation?.field === "reason" || reasonRejected}
                  aria-describedby={validation?.field === "reason" ? "sale-change-validation" : reasonRejected ? "sale-change-failure" : "sale-change-reason-hint"} />
                <span id="sale-change-reason-hint" className="mt-1 block text-xs font-normal text-slate-600">Informe o motivo administrativo em uma linha, sem dados clínicos.</span>
              </label>
            </fieldset>
            {validation && <p role="alert" id="sale-change-validation" className="text-sm text-red-800">{validation.message}</p>}
            {mutation.failure && <p role="alert" id="sale-change-failure" className="rounded-lg bg-red-50 p-3 text-sm text-red-900">{mutation.failure.message}</p>}
            <div className="flex flex-wrap justify-end gap-2">
              <Dialog.Close render={<Button type="button" variant="outline" className="min-h-11" disabled={mutation.pending} />}>Voltar</Dialog.Close>
              {mutation.failure?.needsReload
                ? <Button type="button" className="min-h-11" onClick={onReload}>Recarregar venda</Button>
                : <Button type="submit" variant={correcting ? "default" : "destructive"} className="min-h-11" disabled={mutation.pending}>
                    {mutation.pending ? "Salvando…" : correcting ? "Confirmar correção" : "Confirmar cancelamento"}
                  </Button>}
            </div>
          </form>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
