"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { Dialog } from "@base-ui/react/dialog";
import { useClinic } from "@/components/panel/clinic-context";
import { clinicApiError, clinicContextChanged } from "@/components/panel/clinic-resource";
import { Button } from "@/components/ui/button";
import { withSelectedClinic } from "@/lib/api/auth";
import { isProcedure, saveCatalogItem, setCatalogActive, type CatalogDraft, type CatalogItem, type CatalogKind } from "@/lib/api/catalog";
import { parseCatalogPrice } from "@/lib/catalog-input";

export const catalogInput = "mt-1 min-h-11 w-full rounded-lg border border-slate-400 bg-white px-3 py-2 text-base text-slate-950 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary aria-invalid:border-red-700";
export const singular = { procedures: "procedimento", professionals: "profissional" } as const;
export type CatalogAction = { kind: "create" } | { kind: "edit" | "toggle"; item: CatalogItem };
type Field = "name" | "referencePriceCents" | "category" | "returnIntervalDays";
type Problem = { message: string; reload?: boolean; field?: Field };

function problemFor(code: string, status: number, fallback: string, fields: readonly string[]): Problem {
  if (status === 0 || status >= 500 || (status >= 200 && status < 300)) {
    return { message: "Não foi possível confirmar a alteração. Atualize o catálogo e confira a situação antes de repetir.", reload: true };
  }
  if (code === "PROCEDURE_STALE" || code === "PROFESSIONAL_STALE") {
    return { message: "Outra pessoa alterou este item. Recarregue o catálogo para editar a versão atual.", reload: true };
  }
  if (status === 404) return { message: "Este item não está disponível nesta clínica. Recarregue o catálogo.", reload: true };
  if (code === "PROCEDURE_DUPLICATE" || code === "PROFESSIONAL_DUPLICATE") {
    return { message: "Já existe um item com esse nome. Confira também os inativos.", field: "name" };
  }
  if (code === "PROCEDURE_LIMIT" || code === "PROFESSIONAL_LIMIT") {
    return { message: "A clínica atingiu o limite de cadastros para este tipo de item." };
  }
  const field = fields.find((value): value is Field => ["name", "referencePriceCents", "category", "returnIntervalDays"].includes(value));
  return { message: fallback, field };
}

export function CatalogDialog({ catalog, action, onClose, onReload, onSaved }: {
  catalog: CatalogKind; action: CatalogAction; onClose: () => void; onReload: () => void; onSaved: (message: string) => void;
}) {
  const { clinic, subjectId, refresh } = useClinic();
  const [problem, setProblem] = useState<Problem>();
  const [pending, setPending] = useState(false);
  const formRef = useRef<HTMLFormElement>(null);
  const busy = useRef(false);
  const alive = useRef(false);
  const noun = singular[catalog];
  const item = action.kind === "create" ? undefined : action.item;
  const procedure = item && isProcedure(item) ? item : undefined;
  const toggle = action.kind === "toggle";
  const activating = item?.active === false;
  const title = toggle ? `${activating ? "Ativar" : "Desativar"} ${noun}` : `${item ? "Editar" : "Cadastrar"} ${noun}`;

  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; };
  }, []);

  useEffect(() => {
    if (pending || !problem?.field) return;
    const element = formRef.current?.elements.namedItem(problem.field);
    if (element instanceof HTMLElement) element.focus();
  }, [pending, problem]);

  async function save(send: () => Promise<void>, message: string) {
    if (busy.current || problem?.reload || clinic.permissions.COMMERCIAL_SETTINGS_EDIT !== "ALL") return;
    busy.current = true;
    setPending(true);
    setProblem(undefined);
    try {
      await withSelectedClinic(clinic.tenantId, send, undefined, subjectId);
      if (alive.current) onSaved(message);
    } catch (cause) {
      if (!alive.current) return;
      const error = clinicApiError(cause);
      if (clinicContextChanged(error)) refresh();
      else setProblem(problemFor(error.code, error.status, error.message, error.fields));
    } finally {
      busy.current = false;
      if (alive.current) setPending(false);
    }
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (action.kind === "toggle") {
      void save(() => setCatalogActive(catalog, action.item, activating), activating ? "Item ativado." : "Item desativado.");
      return;
    }
    const fields = new FormData(event.currentTarget);
    const name = String(fields.get("name") ?? "").trim();
    if (!name) {
      setProblem({ message: "Informe um nome.", field: "name" });
      return;
    }
    let draft: CatalogDraft = { kind: "professionals", name };
    if (catalog === "procedures") {
      const referencePriceCents = parseCatalogPrice(String(fields.get("referencePriceCents") ?? ""));
      if (referencePriceCents === null) {
        setProblem({ message: "Informe um valor de 0 a 10 milhões de reais com até duas casas decimais.", field: "referencePriceCents" });
        return;
      }
      const days = String(fields.get("returnIntervalDays") ?? "");
      const returnIntervalDays = days ? Number(days) : null;
      if (returnIntervalDays !== null && (!Number.isInteger(returnIntervalDays) || returnIntervalDays < 1 || returnIntervalDays > 3650)) {
        setProblem({ message: "Informe um intervalo inteiro de 1 a 3.650 dias.", field: "returnIntervalDays" });
        return;
      }
      draft = { kind: catalog, name, referencePriceCents, returnIntervalDays, category: String(fields.get("category") ?? "").trim() || null };
    }
    void save(() => saveCatalogItem(draft, item), item ? "Alterações salvas." : "Cadastro concluído.");
  }

  function close() {
    if (busy.current) return;
    if (problem?.reload) onReload();
    else onClose();
  }

  const described = (field: Field) => problem?.field === field ? "catalog-form-problem" : undefined;
  const locked = pending || Boolean(problem?.reload);
  return (
    <Dialog.Root open onOpenChange={(open) => { if (!open) close(); }}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-slate-950/55" />
        <Dialog.Popup className="fixed top-1/2 left-1/2 z-50 max-h-[90dvh] w-[calc(100%-2rem)] max-w-lg -translate-x-1/2 -translate-y-1/2 space-y-4 overflow-y-auto overscroll-contain rounded-xl border bg-card p-5 shadow-xl">
          <Dialog.Title className="text-xl font-bold">{title}</Dialog.Title>
          <Dialog.Description className="text-sm text-slate-600 wrap-anywhere">
            {toggle
              ? activating ? `“${item?.name}” voltará a aparecer nas opções de novas vendas.` : `“${item?.name}” sairá das opções de novas vendas. O histórico será preservado.`
              : catalog === "professionals" ? "Este cadastro identifica o profissional nas vendas da clínica." : "Cadastre um procedimento para usar no registro de vendas."}
          </Dialog.Description>
          <form method="post" ref={formRef} onSubmit={submit} className="space-y-4">
            {!toggle && (
              <fieldset className="grid gap-4 sm:grid-cols-2" disabled={locked}>
                <legend className="sr-only">Dados do {noun}</legend>
                <label className="text-sm font-semibold sm:col-span-2">Nome
                  <input name="name" required autoComplete="off" maxLength={120} className={catalogInput} defaultValue={item?.name}
                    aria-invalid={problem?.field === "name"} aria-describedby={described("name")} />
                </label>
                {catalog === "procedures" && <>
                  <label className="text-sm font-semibold">Valor de referência (R$)
                    <input name="referencePriceCents" type="text" inputMode="decimal" required autoComplete="off" maxLength={17} className={catalogInput}
                      defaultValue={procedure ? (procedure.referencePriceCents / 100).toFixed(2).replace(".", ",") : ""}
                      aria-invalid={problem?.field === "referencePriceCents"} aria-describedby={described("referencePriceCents")} />
                  </label>
                  <label className="text-sm font-semibold">Categoria <span className="font-normal text-slate-600">(opcional)</span>
                    <input name="category" autoComplete="off" maxLength={60} className={catalogInput} defaultValue={procedure?.category ?? ""}
                      aria-invalid={problem?.field === "category"} aria-describedby={described("category")} />
                  </label>
                  <label className="text-sm font-semibold sm:col-span-2">Intervalo de retorno em dias <span className="font-normal text-slate-600">(opcional)</span>
                    <input name="returnIntervalDays" type="number" min={1} max={3650} step={1} className={catalogInput} defaultValue={procedure?.returnIntervalDays ?? ""}
                      aria-invalid={problem?.field === "returnIntervalDays"} aria-describedby={described("returnIntervalDays")} />
                  </label>
                </>}
              </fieldset>
            )}
            {problem && <p role="alert" id="catalog-form-problem" className="rounded-lg bg-red-50 p-3 text-sm text-red-900">{problem.message}</p>}
            <div className="flex flex-wrap justify-end gap-2">
              <Dialog.Close render={<Button type="button" variant="outline" className="min-h-11" disabled={pending} />}>Cancelar</Dialog.Close>
              {problem?.reload
                ? <Button type="button" className="min-h-11" onClick={onReload}>Recarregar catálogo</Button>
                : <Button type="submit" variant={toggle && !activating ? "destructive" : "default"} className="min-h-11" disabled={pending}>
                    {pending ? "Salvando…" : toggle ? (activating ? "Confirmar ativação" : "Confirmar desativação") : "Salvar"}
                  </Button>}
            </div>
          </form>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
