"use client";

import { useCallback, useState } from "react";
import Link from "next/link";
import { ClipboardList, Plus, Settings2, UsersRound } from "lucide-react";
import { useClinic } from "@/components/panel/clinic-context";
import { useClinicResource } from "@/components/panel/clinic-resource";
import { PanelState } from "@/components/panel/panel-state";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { isProcedure, listCatalog, type CatalogKind } from "@/lib/api/catalog";
import { formatCatalogPrice } from "@/lib/catalog-input";
import { CatalogDialog, catalogInput, singular, type CatalogAction } from "./catalog-dialog";

const options = [
  { kind: "procedures", label: "Procedimentos", icon: ClipboardList },
  { kind: "professionals", label: "Profissionais", icon: UsersRound },
] as const;
type Status = "active" | "inactive" | "all";
const pageSize = 20;
const titles = { procedures: "Procedimentos", professionals: "Profissionais" } as const;

export function CatalogWorkspace() {
  const { clinic } = useClinic();
  const [catalog, setCatalog] = useState<CatalogKind>("procedures");
  const [status, setStatus] = useState<Status>("active");
  const [action, setAction] = useState<CatalogAction>();
  const [page, setPage] = useState(1);
  const [notice, setNotice] = useState("");
  const canEdit = clinic.permissions.COMMERCIAL_SETTINGS_EDIT === "ALL";
  const includeInactive = status !== "active";
  const load = useCallback((signal: AbortSignal) => listCatalog(catalog, includeInactive, signal), [catalog, includeInactive]);
  const { result, reload } = useClinicResource(load);
  const items = result?.data?.filter((item) => status === "all" || item.active === (status === "active"));
  const pages = Math.max(1, Math.ceil((items?.length ?? 0) / pageSize));
  const shownPage = Math.min(page, pages);
  const visibleItems = items?.slice((shownPage - 1) * pageSize, shownPage * pageSize);
  const section = titles[catalog];

  function open(next: CatalogAction) {
    setNotice("");
    setAction(next);
  }

  return (
    <>
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div className="space-y-2">
          <h1 className="font-[family-name:var(--font-branly)] text-3xl font-extrabold tracking-tight sm:text-4xl">Catálogo da clínica</h1>
          <p className="text-sm text-slate-600">Consulte os procedimentos e profissionais disponíveis para as vendas da clínica.</p>
        </div>
        {canEdit && <Link href="/app/funil/configuracao" className={buttonVariants({ variant: "outline", className: "min-h-11 gap-2" })}>
          <Settings2 aria-hidden="true" />Configurar funil
        </Link>}
      </header>
      <div role="group" aria-label="Seções do catálogo" className="flex w-fit flex-wrap gap-1 rounded-lg border border-slate-200 bg-white p-1">
        {options.map(({ kind, label, icon: Icon }) => (
          <Button key={kind} type="button" variant={catalog === kind ? "secondary" : "ghost"} className="min-h-11 gap-2"
            aria-pressed={catalog === kind} onClick={() => { setCatalog(kind); setStatus("active"); setPage(1); setNotice(""); }}>
            <Icon size={16} aria-hidden="true" />{label}
          </Button>
        ))}
      </div>
      <p role="status" className={notice ? "rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-950" : "sr-only"}>{notice}</p>
      <Card aria-labelledby="catalog-section">
        <CardHeader className="flex flex-wrap items-end justify-between gap-3 space-y-0">
          <div className="min-w-0 space-y-1">
            <CardTitle id="catalog-section" className="text-lg">{section}</CardTitle>
            <p className="text-sm text-slate-600">{canEdit ? "Itens inativos ficam fora das opções de novas vendas." : "Seu acesso ao catálogo é de consulta."}</p>
          </div>
          {canEdit && <Button type="button" className="min-h-11 gap-2" onClick={() => open({ kind: "create" })}>
            <Plus size={17} aria-hidden="true" />Cadastrar {singular[catalog]}
          </Button>}
        </CardHeader>
        <CardContent className="space-y-4">
          <label className="block max-w-xs text-sm font-semibold">Situação
            <select name="status" className={catalogInput} value={status} onChange={(event) => { setStatus(event.target.value as Status); setPage(1); }}>
              <option value="active">Ativos</option>
              <option value="inactive">Inativos</option>
              <option value="all">Todos</option>
            </select>
          </label>
          {!result && <PanelState kind="loading" title="Carregando catálogo…" />}
          {result?.error && <PanelState kind={result.error.status === 403 ? "forbidden" : "error"} title="Não foi possível carregar o catálogo"
            message={result.error.message} onRetry={result.error.status === 403 ? undefined : reload} />}
          {visibleItems?.length === 0 && <PanelState kind="empty" title={status === "all" ? `Nenhum ${singular[catalog]} cadastrado` : `Nenhum ${singular[catalog]} ${status === "active" ? "ativo" : "inativo"}`}
            message={status === "all" && canEdit ? "Use o botão de cadastro para adicionar o primeiro item." : "Altere o filtro para consultar outras situações."} />}
          {visibleItems && visibleItems.length > 0 && (
            <ul className="divide-y divide-border border-y border-border">
              {visibleItems.map((item) => (
                <li key={item.id} className="flex flex-wrap items-center justify-between gap-3 py-4">
                  <div className="min-w-0 space-y-2">
                    <div className="flex flex-wrap items-center gap-2">
                      <h3 className="min-w-0 font-semibold wrap-anywhere">{item.name}</h3>
                      <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${item.active ? "bg-emerald-50 text-emerald-800" : "bg-slate-100 text-slate-700"}`}>
                        {item.active ? "Ativo" : "Inativo"}
                      </span>
                    </div>
                    {isProcedure(item) && (
                      <dl className="flex flex-wrap gap-x-6 gap-y-2 text-sm">
                        <div><dt className="text-xs text-slate-600">Valor de referência</dt><dd className="font-medium tabular-nums">{formatCatalogPrice(item.referencePriceCents)}</dd></div>
                        {item.category && <div className="min-w-0"><dt className="text-xs text-slate-600">Categoria</dt><dd className="wrap-anywhere">{item.category}</dd></div>}
                        {item.returnIntervalDays != null && <div><dt className="text-xs text-slate-600">Intervalo de retorno</dt><dd>{item.returnIntervalDays.toLocaleString("pt-BR")} {item.returnIntervalDays === 1 ? "dia" : "dias"}</dd></div>}
                      </dl>
                    )}
                  </div>
                  {canEdit && <div className="flex flex-wrap gap-2">
                    <Button type="button" variant="outline" className="min-h-11" aria-label={`Editar ${item.name}`} onClick={() => open({ kind: "edit", item })}>Editar</Button>
                    <Button type="button" variant="outline" className="min-h-11" aria-label={`${item.active ? "Desativar" : "Ativar"} ${item.name}`} onClick={() => open({ kind: "toggle", item })}>
                      {item.active ? "Desativar" : "Ativar"}
                    </Button>
                  </div>}
                </li>
              ))}
            </ul>
          )}
          {items && pages > 1 && (
            <nav aria-label="Paginação do catálogo" className="flex flex-wrap items-center justify-between gap-2">
              <p role="status" className="text-sm text-slate-600 tabular-nums">Página {shownPage} de {pages} · {items.length.toLocaleString("pt-BR")} itens</p>
              <div className="flex gap-2">
                <Button type="button" variant="outline" className="min-h-11" disabled={shownPage === 1} onClick={() => setPage(shownPage - 1)}>Anterior</Button>
                <Button type="button" variant="outline" className="min-h-11" disabled={shownPage === pages} onClick={() => setPage(shownPage + 1)}>Próxima</Button>
              </div>
            </nav>
          )}
        </CardContent>
      </Card>
      {action && canEdit && <CatalogDialog catalog={catalog} action={action}
        onClose={() => setAction(undefined)}
        onReload={() => { setAction(undefined); reload(); }}
        onSaved={(message) => {
          if (action.kind === "create") {
            if (status === "inactive") setStatus("active");
            setPage(1);
          }
          setAction(undefined);
          setNotice(message);
          reload();
        }} />}
    </>
  );
}
