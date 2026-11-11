"use client";

import { useCallback, useRef, useState, type FormEvent } from "react";
import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { FunnelHeading, fieldClass, useFunnelResource } from "@/components/funnel/funnel-ui";
import { PanelState } from "@/components/panel/panel-state";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { getSaleProcedures, listSales, type SaleFilters, type SaleStatus } from "@/lib/api/sale-management";
import { formatSaleAmount } from "@/lib/sale-amount";

const initial: SaleFilters = { status: "", from: "", to: "", page: 1 };
const date = new Intl.DateTimeFormat("pt-BR", { dateStyle: "short", timeZone: "UTC" });
export const formatSaleDay = (value: string) => date.format(new Date(value));

export function SaleBadge({ status }: { status: SaleStatus }) {
  return <span className={`inline-flex rounded-full px-2.5 py-1 text-xs font-semibold ${status === "ACTIVE" ? "bg-emerald-50 text-emerald-800" : "bg-slate-100 text-slate-700"}`}>
    {status === "ACTIVE" ? "Ativa" : "Cancelada"}
  </span>;
}

export function SalesWorkspace() {
  const [filters, setFilters] = useState(initial);
  const [rangeError, setRangeError] = useState("");
  const formRef = useRef<HTMLFormElement>(null);
  const load = useCallback((signal: AbortSignal) => listSales(filters, signal), [filters]);
  const { result, reload } = useFunnelResource(load);
  const catalog = useFunnelResource(getSaleProcedures);
  const page = result?.data;
  const last = page ? Math.min(Math.max(1, Math.ceil(page.total / page.pageSize)), Math.floor(10_000 / page.pageSize) + 1) : 1;

  function apply(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const values = new FormData(event.currentTarget);
    const from = String(values.get("from") ?? "");
    const to = String(values.get("to") ?? "");
    if (from && to && from > to) {
      setRangeError("A data inicial deve ser anterior ou igual à final.");
      const input = event.currentTarget.elements.namedItem("from");
      if (input instanceof HTMLElement) input.focus();
      return;
    }
    setRangeError("");
    setFilters({ from, to, status: values.get("status") as SaleFilters["status"], page: 1 });
  }

  return (
    <>
      <FunnelHeading title="Vendas" description="Consulte as vendas da clínica, o valor registrado e o histórico de alterações." />
      <Card aria-labelledby="sale-filters">
        <CardHeader><CardTitle id="sale-filters">Filtrar vendas</CardTitle></CardHeader>
        <CardContent>
          <form ref={formRef} method="post" onSubmit={apply} className="grid items-end gap-3 md:grid-cols-4">
            <label className="text-sm font-semibold">Situação
              <select name="status" className={fieldClass}>
                <option value="">Todas</option><option value="ACTIVE">Ativas</option><option value="CANCELLED">Canceladas</option>
              </select>
            </label>
            <label className="text-sm font-semibold">Data inicial
              <input name="from" type="date" className={fieldClass} aria-invalid={Boolean(rangeError)} aria-describedby={rangeError ? "sales-range-error" : undefined} />
            </label>
            <label className="text-sm font-semibold">Data final<input name="to" type="date" className={fieldClass} /></label>
            <div className="flex flex-wrap gap-2">
              <Button type="submit" className="min-h-11">Aplicar filtros</Button>
              {(filters.status || filters.from || filters.to || rangeError) && (
                <Button type="button" variant="outline" className="min-h-11" onClick={() => { formRef.current?.reset(); setRangeError(""); setFilters(initial); }}>Limpar</Button>
              )}
            </div>
            {rangeError && <p id="sales-range-error" role="alert" className="text-sm text-red-800 md:col-span-4">{rangeError}</p>}
          </form>
        </CardContent>
      </Card>
      {!result && <PanelState kind="loading" title="Carregando vendas…" />}
      {result?.error && <PanelState kind={result.error.status === 403 ? "forbidden" : "error"} title="Não foi possível listar as vendas" message={result.error.message} onRetry={reload} />}
      {catalog.result?.error && <PanelState kind="error" title="Nomes dos procedimentos indisponíveis" message="As vendas ainda podem ser consultadas pelo valor e pela data." onRetry={catalog.reload} />}
      {page && page.items.length === 0 && <PanelState kind="empty" title="Nenhuma venda encontrada" message="Quando houver vendas neste período e situação, elas aparecem aqui." />}
      {page && page.items.length > 0 && (
        <Card aria-label="Vendas registradas" className="p-4 sm:p-5">
          <ul className="divide-y divide-border">
            {page.items.map((sale) => {
              const procedure = catalog.result?.data?.find(({ id }) => id === sale.procedureId);
              return (
                <li key={sale.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                  <div className="min-w-0 space-y-1">
                    <p className="font-semibold wrap-anywhere">{procedure?.name ?? "Venda registrada"}</p>
                    <p className="text-sm text-slate-600 tabular-nums">{formatSaleDay(sale.soldOn)}</p>
                  </div>
                  <div className="flex flex-wrap items-center gap-3">
                    <p className="font-bold tabular-nums">{formatSaleAmount(sale.amountCents)}</p>
                    <SaleBadge status={sale.status} />
                    <Link href={`/app/vendas/${sale.id}`} prefetch={false}
                      className="inline-flex min-h-11 items-center gap-2 rounded-sm text-sm font-semibold text-[#174578] hover:underline focus-visible:outline-2"
                      aria-label={`Ver venda de ${procedure?.name ?? "procedimento"} em ${formatSaleDay(sale.soldOn)}, ${formatSaleAmount(sale.amountCents)}`}>
                      Ver venda <ArrowRight size={15} aria-hidden="true" />
                    </Link>
                  </div>
                </li>
              );
            })}
          </ul>
        </Card>
      )}
      {page && (last > 1 || page.page > 1) && (
        <nav aria-label="Paginação de vendas" className="flex flex-wrap items-center justify-between gap-2">
          <p role="status" className="text-sm text-slate-600 tabular-nums">Página {page.page} de {last} · {page.total.toLocaleString("pt-BR")} vendas</p>
          <div className="flex gap-2">
            <Button type="button" variant="outline" className="min-h-11" disabled={page.page <= 1} onClick={() => setFilters({ ...filters, page: Math.min(last, page.page - 1) })}>Anterior</Button>
            <Button type="button" variant="outline" className="min-h-11" disabled={page.page >= last} onClick={() => setFilters({ ...filters, page: page.page + 1 })}>Próxima</Button>
          </div>
        </nav>
      )}
    </>
  );
}
