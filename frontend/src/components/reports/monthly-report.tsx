"use client";

import { useCallback, useState, useSyncExternalStore, type ReactNode } from "react";
import { useClinicResource } from "@/components/panel/clinic-resource";
import { PanelState } from "@/components/panel/panel-state";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import type { ApiError } from "@/lib/api/client";
import { getMonthlyReport, reportProblem, type MonthlyReport } from "@/lib/api/reports";
import { formatSaleAmount } from "@/lib/sale-amount";
import { ReportDistribution } from "./report-distribution";

const number = new Intl.NumberFormat("pt-BR");
const percent = new Intl.NumberFormat("pt-BR", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const monthName = new Intl.DateTimeFormat("pt-BR", { month: "long", year: "numeric", timeZone: "UTC" });
const firstYear = 2000;
const calendarMonth = new Intl.DateTimeFormat("pt-BR", { month: "long", timeZone: "UTC" });
const months = Array.from({ length: 12 }, (_, index) => ({
  value: String(index + 1).padStart(2, "0"),
  label: calendarMonth.format(new Date(Date.UTC(2000, index, 1))),
}));
const selectClass = "min-h-11 max-w-full rounded-lg border border-input bg-card px-3 text-base focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary";

const origin = new Map([
  ["PAID_TRAFFIC", "Tráfego pago"], ["INSTAGRAM_ORGANIC", "Instagram orgânico"], ["REFERRAL", "Indicação"],
  ["GOOGLE", "Google"], ["OTHER", "Outra origem"], ["UNIDENTIFIED", "Não identificada"],
]);
const temperature = new Map([
  ["COLD", "Frio"], ["WARM", "Morno"], ["HOT", "Quente"], ["QUALIFIED", "Qualificado"], ["NONE", "Sem temperatura"],
]);

function ReportError({ error, reload }: { error: ApiError; reload: () => void }) {
  return <PanelState kind={error.status === 403 ? "forbidden" : "error"}
    title="Não foi possível carregar o relatório" message={reportProblem(error)}
    onRetry={error.status === 403 ? undefined : reload} />;
}

function Metric({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="min-w-0 rounded-lg border bg-card p-4">
      <dt className="text-sm font-medium text-muted-foreground">{label}</dt>
      <dd className="mt-1 text-2xl font-bold tabular-nums wrap-anywhere">{children}</dd>
    </div>
  );
}

function ReportData({ report }: { report: MonthlyReport }) {
  const label = monthName.format(new Date(`${report.month}-01T00:00:00Z`));
  const noActivity = ![report.contactsCreated, report.salesClosed, report.lost, report.salesCount,
    ...report.leadsByOrigin.map(({ count }) => count), ...report.leadsByTemperature.map(({ count }) => count)].some(Boolean);
  return (
    <section aria-labelledby="report-period" className="space-y-5">
      <div>
        <h2 id="report-period" className="text-xl font-bold first-letter:uppercase">{label}</h2>
        <p className="mt-1 text-sm text-muted-foreground" translate="no">{report.timeZone}</p>
      </div>
      {noActivity && <PanelState kind="empty" title="Nenhum registro neste mês" message="Escolha outro mês para consultar o histórico da clínica." />}
      <dl className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <Metric label="Contatos novos">{number.format(report.contactsCreated)}</Metric>
        <Metric label="Vendas fechadas">{number.format(report.salesClosed)}</Metric>
        <Metric label="Perdidos">{number.format(report.lost)}</Metric>
        <Metric label="Taxa de conversão">
          {report.conversionPercent === null
            ? <><span aria-hidden="true">—</span><span className="sr-only">Sem leads encerrados no período</span></>
            : `${percent.format(report.conversionPercent)}%`}
        </Metric>
        <Metric label="Frios revertidos">{number.format(report.coldReverted)}</Metric>
        <Metric label="Faturamento registrado">
          {formatSaleAmount(report.revenueCents)}
          <span className="mt-1 block text-sm font-normal text-muted-foreground">{number.format(report.salesCount)} {report.salesCount === 1 ? "venda registrada" : "vendas registradas"}</span>
        </Metric>
      </dl>
      <p className="text-sm text-muted-foreground">O faturamento registrado usa a data informada na venda. Vendas fechadas usam a data em que o lead foi ganho.</p>
      <ReportDistribution id="report-origins" title="Leads por origem"
        rows={report.leadsByOrigin.map(({ key, count }) => ({ key, count, label: origin.get(key) ?? key }))} />
      <ReportDistribution id="report-temperatures" title="Leads por temperatura"
        rows={report.leadsByTemperature.map(({ key, count }) => ({ key, count, label: temperature.get(key) ?? key }))} />
      <div className="grid items-start gap-5 xl:grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)]">
        <Card aria-labelledby="report-lost">
          <CardHeader><CardTitle id="report-lost">Perdidos por motivo</CardTitle></CardHeader>
          <CardContent>
            {report.lostByReason.length ? (
              <ul className="divide-y">
                {report.lostByReason.map(({ id, name, count }) => (
                  <li key={id} className="flex justify-between gap-3 py-2 text-sm">
                    <span className="min-w-0 wrap-anywhere">{name}</span><span className="font-semibold tabular-nums">{number.format(count)}</span>
                  </li>
                ))}
              </ul>
            ) : <p className="text-sm text-muted-foreground">Nenhuma perda registrada no período.</p>}
          </CardContent>
        </Card>
        <Card aria-labelledby="report-procedures">
          <CardHeader><CardTitle id="report-procedures">Procedimentos mais vendidos</CardTitle></CardHeader>
          <CardContent>
            {report.topProcedures.length ? (
              <ol className="divide-y">
                {report.topProcedures.map(({ id, name, count, amountCents }, index) => (
                  <li key={id} className="grid min-w-0 gap-1 py-2 text-sm sm:grid-cols-[minmax(0,1fr)_auto] sm:gap-3">
                    <span className="min-w-0 wrap-anywhere"><span className="mr-2 text-muted-foreground tabular-nums">{index + 1}.</span>{name}</span>
                    <span className="text-muted-foreground tabular-nums">{number.format(count)} {count === 1 ? "venda" : "vendas"} · <strong className="text-foreground">{formatSaleAmount(amountCents)}</strong></span>
                  </li>
                ))}
              </ol>
            ) : <p className="text-sm text-muted-foreground">Nenhum procedimento vendido no período.</p>}
          </CardContent>
        </Card>
      </div>
    </section>
  );
}

function SelectedMonth({ current, preferredMonth, setMonth }: {
  current: MonthlyReport; preferredMonth: string; setMonth: (value: string) => void;
}) {
  const month = preferredMonth && preferredMonth <= current.month ? preferredMonth : current.month;
  const [selectedYear, selectedMonth] = month.split("-");
  const [currentYear, currentMonth] = current.month.split("-");
  const years = Array.from({ length: Number(currentYear) - firstYear + 1 }, (_, index) => Number(currentYear) - index);
  const visibleMonths = selectedYear === currentYear ? months.slice(0, Number(currentMonth)) : months;
  const load = useCallback((signal: AbortSignal) => month === current.month
    ? Promise.resolve(current) : getMonthlyReport(month, signal), [current, month]);
  const { result, reload } = useClinicResource(load);
  return (
    <>
      <fieldset className="flex flex-wrap gap-3">
        <legend className="mb-2 text-sm font-semibold">Período de referência</legend>
        <label className="grid gap-1 text-sm text-muted-foreground">Mês
          <select name="month" value={selectedMonth} className={selectClass}
            onChange={({ target }) => setMonth(`${selectedYear}-${target.value}`)}>
            {visibleMonths.map(({ value, label }) => <option key={value} value={value}>{label}</option>)}
          </select>
        </label>
        <label className="grid gap-1 text-sm text-muted-foreground">Ano
          <select name="year" value={selectedYear} className={selectClass} onChange={({ target }) => {
            const year = target.value;
            const nextMonth = year === currentYear && selectedMonth > currentMonth ? currentMonth : selectedMonth;
            setMonth(`${year}-${nextMonth}`);
          }}>
            {years.map((year) => <option key={year}>{year}</option>)}
          </select>
        </label>
      </fieldset>
      <div aria-busy={!result}>
        {!result && <PanelState kind="loading" title="Carregando relatório…" />}
        {result?.error && <ReportError error={result.error} reload={reload} />}
        {result?.data && <ReportData report={result.data} />}
      </div>
    </>
  );
}

const loadCurrentMonth = (signal: AbortSignal) => getMonthlyReport(undefined, signal);

function MonthlyReportContent({ preferredMonth, setMonth }: { preferredMonth: string; setMonth: (value: string) => void }) {
  const { result, reload } = useClinicResource(loadCurrentMonth);
  return (
    <>
      {!result && <PanelState kind="loading" title="Carregando relatório…" />}
      {result?.error && <ReportError error={result.error} reload={reload} />}
      {result?.data && <SelectedMonth current={result.data} preferredMonth={preferredMonth} setMonth={setMonth} />}
    </>
  );
}

function subscribeVisibility(onChange: () => void) {
  document.addEventListener("visibilitychange", onChange);
  return () => document.removeEventListener("visibilitychange", onChange);
}

export function MonthlyReportWorkspace() {
  const [preferredMonth, setMonth] = useState("");
  const visible = useSyncExternalStore(subscribeVisibility, () => document.visibilityState === "visible", () => false);
  return (
    <>
      <header>
        <h1 className="font-[family-name:var(--font-branly)] text-3xl font-extrabold tracking-tight text-balance sm:text-4xl">Relatório mensal</h1>
        <p className="mt-2 text-sm text-muted-foreground">Acompanhe contatos, leads e vendas da clínica por mês.</p>
      </header>
      {visible && <MonthlyReportContent preferredMonth={preferredMonth} setMonth={setMonth} />}
    </>
  );
}
