"use client";

import { useRef, useState, type FormEvent } from "react";
import { Dialog } from "@base-ui/react/dialog";
import { Button } from "@/components/ui/button";
import { PanelState } from "@/components/panel/panel-state";
import type { Lead } from "@/lib/api/leads";
import { getSaleOptions, paymentMethods, registerSale } from "@/lib/api/sales";
import { formatSaleAmount, parseSaleAmount } from "@/lib/sale-amount";
import { fieldClass, FunnelProblem, useFunnelResource, type useLeadMutation } from "./funnel-ui";

export function RegisterSaleDialog({ lead, mutation, onClose, onReload, onRegistered }: {
  lead: Lead;
  mutation: ReturnType<typeof useLeadMutation>;
  onClose: () => void;
  onReload: () => void;
  onRegistered: () => void;
}) {
  const { result, reload } = useFunnelResource(getSaleOptions);
  const [procedureId, setProcedureId] = useState("");
  const [amountError, setAmountError] = useState(false);
  const amountInput = useRef<HTMLInputElement>(null);
  const selectedProcedure = result?.data?.procedures.find((procedure) => procedure.id === procedureId);
  const disabled = mutation.pending || Boolean(mutation.failure?.needsReload);
  const hasProcedures = Boolean(result?.data?.procedures.length);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (disabled || !selectedProcedure || !result?.data) return;

    const form = new FormData(event.currentTarget);
    const amountCents = parseSaleAmount(String(form.get("amount") ?? ""));
    if (amountCents === null) {
      setAmountError(true);
      amountInput.current?.focus();
      return;
    }

    const professionalId = result.data.professionals.find(({ id }) => id === form.get("professionalId"))?.id;
    const payment = String(form.get("paymentMethod") ?? "");
    const paymentMethod = Object.hasOwn(paymentMethods, payment) ? payment as keyof typeof paymentMethods : undefined;
    const soldOn = String(form.get("soldOn") ?? "") || undefined;
    setAmountError(false);
    void mutation.run(() => registerSale(lead, { procedureId: selectedProcedure.id, amountCents, professionalId, paymentMethod, soldOn }), onRegistered);
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
          <Dialog.Title className="text-xl font-bold">Registrar venda</Dialog.Title>
          <Dialog.Description className="text-sm text-slate-700">Ao confirmar a venda, o lead será movido para Venda fechada.</Dialog.Description>
          {!result && <PanelState kind="loading" title="Carregando catálogo…" />}
          {result?.error && <FunnelProblem error={result.error} retry={reload} />}
          {result?.data?.procedures.length === 0 && <PanelState kind="empty" title="Nenhum procedimento ativo" message="Peça a um gestor para cadastrar ou ativar um procedimento antes de registrar a venda." />}
          {result?.data && result.data.procedures.length > 0 && (
            <form id="register-sale-form" method="post" onSubmit={submit} className="space-y-4">
              <fieldset disabled={disabled} className="grid gap-4 sm:grid-cols-2">
                <legend className="sr-only">Dados da venda</legend>
                <label className="block text-sm font-semibold">Procedimento
                  <select className={fieldClass} name="procedureId" required value={procedureId} onChange={(event) => setProcedureId(event.target.value)}>
                    <option value="">Selecione um procedimento</option>
                    {result.data.procedures.map((procedure) => <option key={procedure.id} value={procedure.id}>{procedure.name}</option>)}
                  </select>
                </label>
                <div>
                  <label htmlFor="sale-amount" className="block text-sm font-semibold">Valor da venda (R$)</label>
                  <input ref={amountInput} id="sale-amount" name="amount" type="text" inputMode="decimal" autoComplete="off" maxLength={17} required
                    className={fieldClass} aria-invalid={amountError} aria-describedby={amountError ? "sale-amount-error" : "sale-amount-hint"}
                    onChange={() => { if (amountError) setAmountError(false); }} />
                  <span id="sale-amount-hint" className="mt-1 block text-xs font-normal text-slate-600">
                    {selectedProcedure?.referencePriceCents != null ? `Referência do catálogo: ${formatSaleAmount(selectedProcedure.referencePriceCents)}.` : "Exemplo: 250,00."}
                  </span>
                  {amountError && <p role="alert" id="sale-amount-error" className="mt-1 text-sm text-red-800">Informe um valor acima de zero, de até R$ 10 milhões e com até duas casas decimais.</p>}
                </div>
                <label className="block text-sm font-semibold">Profissional <span className="font-normal text-slate-600">(opcional)</span>
                  <select className={fieldClass} name="professionalId" defaultValue="">
                    <option value="">Não informado</option>
                    {result.data.professionals.map((professional) => <option key={professional.id} value={professional.id}>{professional.name}</option>)}
                  </select>
                </label>
                <label className="block text-sm font-semibold">Forma de pagamento <span className="font-normal text-slate-600">(opcional)</span>
                  <select className={fieldClass} name="paymentMethod" defaultValue="">
                    <option value="">Não informada</option>
                    {Object.entries(paymentMethods).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
                  </select>
                </label>
                <label className="block text-sm font-semibold">Data da venda <span className="font-normal text-slate-600">(opcional)</span>
                  <input className={fieldClass} name="soldOn" type="date" min="2000-01-01" aria-describedby="sale-date-hint" />
                  <span id="sale-date-hint" className="mt-1 block text-xs font-normal text-slate-600">Em branco, usa a data de hoje no fuso da clínica.</span>
                </label>
              </fieldset>
              {mutation.failure && <p role="alert" className="rounded-lg bg-red-50 p-3 text-sm text-red-900">{mutation.failure.message}</p>}
            </form>
          )}
          <div className="flex flex-wrap justify-end gap-2">
            <Dialog.Close render={<Button type="button" variant="outline" className="min-h-11" disabled={mutation.pending} />}>{hasProcedures ? "Cancelar" : "Fechar"}</Dialog.Close>
            {hasProcedures && (mutation.failure?.needsReload
              ? <Button type="button" className="min-h-11" onClick={onReload}>Atualizar dados</Button>
              : <Button type="submit" form="register-sale-form" className="min-h-11" disabled={mutation.pending}>{mutation.pending ? "Registrando…" : "Confirmar venda"}</Button>)}
          </div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
