"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { CalendarDays, ClipboardCheck, Clock3, Columns3, ReceiptText, type LucideIcon } from "lucide-react";
import { useClinic } from "@/components/panel/clinic-context";
import { clinicApiError, clinicContextChanged } from "@/components/panel/clinic-resource";
import { PanelState } from "@/components/panel/panel-state";
import { Card, CardHeader, CardTitle } from "@/components/ui/card";
import { withSelectedClinic } from "@/lib/api/auth";
import type { ApiError } from "@/lib/api/client";
import { getDashboard, reportProblem, type Dashboard } from "@/lib/api/reports";
import { formatSaleAmount } from "@/lib/sale-amount";
import { loginRetryDeadline } from "@/lib/api/retry-after";
import { taskPath } from "@/lib/api/tasks";

const refreshMs = 60_000;
const countFormat = new Intl.NumberFormat("pt-BR");
const dateFormat = new Intl.DateTimeFormat("pt-BR", { dateStyle: "long", timeZone: "UTC" });

function Indicator({ title, value, hint, href, icon: Icon }: {
  title: string; value: string; hint?: string; href?: string; icon: LucideIcon;
}) {
  return (
    <Card className="min-w-0">
      <CardHeader className="space-y-3">
        <div className="flex items-center gap-3">
          <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-primary/10 text-primary">
            <Icon size={18} aria-hidden="true" />
          </span>
          <CardTitle>{href ? <Link href={href} className="inline-flex min-h-11 items-center rounded-sm text-primary underline-offset-4 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2">{title}</Link> : title}</CardTitle>
        </div>
        <p className="text-2xl font-bold tabular-nums wrap-anywhere">{value}</p>
        {hint && <p className="text-sm text-muted-foreground">{hint}</p>}
      </CardHeader>
    </Card>
  );
}

export function ReceptionDashboard() {
  const { clinic, subjectId, refresh } = useClinic();
  const [data, setData] = useState<Dashboard>();
  const [error, setError] = useState<ApiError>();
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    let active: AbortController | undefined;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let retryAt = 0;
    let denied = false;

    async function load() {
      if (document.visibilityState !== "visible" || active || denied) return;
      if (Date.now() < retryAt) {
        timer = setTimeout(load, Math.min(retryAt - Date.now(), 2_147_483_647));
        return;
      }
      const request = new AbortController();
      active = request;
      try {
        const result = await withSelectedClinic(clinic.tenantId, () => getDashboard(request.signal), request.signal, subjectId);
        if (request.signal.aborted) return;
        setData(result);
        setError(undefined);
      } catch (cause) {
        if (request.signal.aborted) return;
        const failure = clinicApiError(cause);
        if (clinicContextChanged(failure)) {
          setData(undefined);
          refresh();
        } else {
          if (failure.status === 401 || failure.status === 403) {
            setData(undefined);
            denied = true;
          }
          if (failure.status === 503 && failure.retryAfter) retryAt = loginRetryDeadline(failure.retryAfter, Date.now());
          setError(failure);
        }
      } finally {
        if (active === request) active = undefined;
        if (!request.signal.aborted && !denied && document.visibilityState === "visible") {
          timer = setTimeout(load, refreshMs);
        }
      }
    }

    function visibilityChanged() {
      clearTimeout(timer);
      if (document.visibilityState === "visible") void load();
      else {
        active?.abort();
        active = undefined;
        setData(undefined);
        setError((failure) => failure?.status === 403 ? failure : undefined);
      }
    }

    document.addEventListener("visibilitychange", visibilityChanged);
    void load();
    return () => {
      document.removeEventListener("visibilitychange", visibilityChanged);
      clearTimeout(timer);
      active?.abort();
    };
  }, [clinic.tenantId, refresh, revision, subjectId]);

  const retry = () => { setError(undefined); setRevision((value) => value + 1); };
  const empty = data && ![data.leadsToday, data.salesTodayCount, data.salesMonthCount, data.tasksOverdue, data.tasksToday].some(Boolean);
  return (
    <section className="min-w-0 space-y-4" aria-labelledby="dashboard-title">
      <header>
        <h2 id="dashboard-title" className="text-xl font-bold">Indicadores da clínica</h2>
        {data && (
          <p className="mt-1 text-sm text-muted-foreground">
            <time dateTime={data.today}>{dateFormat.format(new Date(`${data.today}T00:00:00Z`))}</time>
            {" · "}<span translate="no">{data.timeZone}</span>
          </p>
        )}
      </header>
      {!data && !error && <PanelState kind="loading" title="Carregando indicadores…" />}
      {error && <PanelState kind={error.status === 403 ? "forbidden" : "error"}
        title={data ? "Indicadores aguardando atualização" : "Não foi possível carregar os indicadores"}
        message={reportProblem(error)} onRetry={error.status === 403 || (error.status === 503 && error.retryAfter) ? undefined : retry} />}
      {empty && <p className="text-sm text-muted-foreground">Nenhum registro nos períodos exibidos.</p>}
      {data && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5">
          <Indicator title="Leads de hoje" value={countFormat.format(data.leadsToday)} icon={Columns3} />
          <Indicator title="Vendas de hoje" value={formatSaleAmount(data.salesTodayCents)}
            hint={`${countFormat.format(data.salesTodayCount)} ${data.salesTodayCount === 1 ? "venda" : "vendas"} · Faturamento registrado`} icon={ReceiptText} />
          <Indicator title="Vendas do mês" value={formatSaleAmount(data.salesMonthCents)}
            hint={`${countFormat.format(data.salesMonthCount)} ${data.salesMonthCount === 1 ? "venda" : "vendas"} · Faturamento registrado`} icon={CalendarDays} />
          <Indicator title="Tarefas atrasadas" value={countFormat.format(data.tasksOverdue)} icon={Clock3}
            href={clinic.permissions.TASK_READ === "ALL" ? taskPath("OVERDUE") : undefined} />
          <Indicator title="Tarefas de hoje" value={countFormat.format(data.tasksToday)} icon={ClipboardCheck}
            href={clinic.permissions.TASK_READ === "ALL" ? taskPath("TODAY") : undefined} />
        </div>
      )}
    </section>
  );
}
