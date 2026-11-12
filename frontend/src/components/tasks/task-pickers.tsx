"use client";

import { useCallback, useEffect, useState } from "react";
import { useClinicResource } from "@/components/panel/clinic-resource";
import { Button } from "@/components/ui/button";
import { fieldClass, statusLabel } from "@/components/funnel/funnel-ui";
import { listContacts } from "@/lib/api/contacts";
import { listLeads, type LeadStatus } from "@/lib/api/leads";
import { listMembers } from "@/lib/api/team";
import { taskProblem } from "@/lib/api/tasks";
import type { ApiError } from "@/lib/api/client";

type Choice = { id: string; label: string };
type PickerProps = { value: Choice; onSelect: (choice: Choice) => void; disabled: boolean };

export function TaskPages({ page, pageSize, total, change, label }: {
  page: number; pageSize: number; total: number; change: (page: number) => void; label: string;
}) {
  const last = Math.min(Math.ceil(total / pageSize), Math.floor(10_000 / pageSize) + 1);
  if (page === 1 && last < 2) return null;
  return (
    <nav className="flex flex-wrap items-center gap-2" aria-label={label}>
      <Button type="button" variant="outline" className="min-h-11" disabled={page <= 1} onClick={() => change(page - 1)}>Anterior</Button>
      <span className="text-sm text-muted-foreground tabular-nums">Página {page}</span>
      <Button type="button" variant="outline" className="min-h-11" disabled={page >= last} onClick={() => change(page + 1)}>Próxima</Button>
    </nav>
  );
}

function LoadChoices({ error, reload }: { error?: ApiError; reload: () => void }) {
  return error
    ? <div className="flex flex-wrap items-center gap-2"><p role="alert" className="text-sm text-destructive">{taskProblem(error).message}</p><Button type="button" variant="outline" className="min-h-11" onClick={reload}>Tentar novamente</Button></div>
    : <p role="status" className="text-sm text-muted-foreground">Carregando opções…</p>;
}

function ChoiceSelect({ label, options, value, onSelect, disabled, placeholder, required }: PickerProps & {
  label: string; options: Choice[]; placeholder: string; required?: boolean;
}) {
  return (
    <label className="block text-sm font-semibold">{label}
      <select className={fieldClass} value={value.id} disabled={disabled} required={required}
        onChange={(event) => onSelect(options.find(({ id }) => id === event.target.value) ?? { id: "", label: "" })}>
        <option value="">{placeholder}</option>
        {value.id && !options.some(({ id }) => id === value.id) && <option value={value.id}>{value.label}</option>}
        {options.map(({ id, label }) => <option key={id} value={id}>{label}</option>)}
      </select>
    </label>
  );
}

export function ContactPicker(props: PickerProps) {
  const [search, setSearch] = useState("");
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(1);
  useEffect(() => {
    const timer = setTimeout(() => { setQuery(search.trim()); setPage(1); }, 250);
    return () => clearTimeout(timer);
  }, [search]);
  const load = useCallback((signal: AbortSignal) => listContacts({ query, tagId: "", includeArchived: false, page }, signal), [query, page]);
  const { result, reload } = useClinicResource(load);
  const data = result?.data;
  return (
    <div className="space-y-2">
      <label className="block text-sm font-semibold">Buscar contato
        <input type="search" value={search} onChange={(event) => setSearch(event.target.value)} className={fieldClass}
          disabled={props.disabled} maxLength={100} onKeyDown={(event) => { if (event.key === "Enter") event.preventDefault(); }} />
      </label>
      <ChoiceSelect {...props} disabled={props.disabled || !data || query !== search.trim()} required label="Contato"
        placeholder="Selecione um contato" options={data?.items.map(({ id, name, phone }) => ({ id, label: `${name} · ${phone}` })) ?? []} />
      {data ? (data.total === 0 ? <p role="status" className="text-sm text-muted-foreground">Nenhum contato encontrado.</p>
        : <TaskPages {...data} change={setPage} label="Páginas de contatos" />) : <LoadChoices error={result?.error} reload={reload} />}
    </div>
  );
}

export function LeadPicker({ contactId, timeZone, ...props }: PickerProps & { contactId: string; timeZone: string }) {
  const [page, setPage] = useState(1);
  const [status, setStatus] = useState<LeadStatus>("OPEN");
  const load = useCallback((signal: AbortSignal) => listLeads({ contactId, page, status }, signal), [contactId, page, status]);
  const { result, reload } = useClinicResource(load);
  const data = result?.data;
  const date = new Intl.DateTimeFormat("pt-BR", { dateStyle: "short", timeStyle: "short", timeZone });
  return (
    <div className="space-y-2">
      <label className="block text-sm font-semibold">Situação do lead
        <select className={fieldClass} value={status} disabled={props.disabled} onChange={(event) => {
          setStatus(event.target.value as LeadStatus);
          setPage(1);
        }}>
          {Object.entries(statusLabel).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
      <ChoiceSelect {...props} disabled={props.disabled || !data} label="Lead (opcional)" placeholder="Sem lead"
        options={data?.items.map(({ id, openedAt, status }) => ({ id, label: `${statusLabel[status]} · ${date.format(new Date(openedAt))}` })) ?? []} />
      {data ? <TaskPages {...data} change={setPage} label="Páginas de leads" /> : <LoadChoices error={result?.error} reload={reload} />}
    </div>
  );
}

export function ResponsiblePicker({ subjectId, ...props }: PickerProps & { subjectId: string }) {
  const [page, setPage] = useState(1);
  const load = useCallback(async (signal: AbortSignal) => {
    const { items, ...paging } = await listMembers({ page, role: "", active: "true" }, signal);
    return {
      ...paging,
      items: items.filter(({ active, role }) => active && role !== "VIEWER")
        .map(({ subjectId: id, displayName }) => ({ id, label: displayName })),
    };
  }, [page]);
  const { result, reload } = useClinicResource(load);
  const data = result?.data;
  const choices = [{ id: subjectId, label: "Você" }, ...(data?.items ?? []).filter(({ id }) => id !== subjectId)];
  return (
    <div className="space-y-2">
      <ChoiceSelect {...props} disabled={props.disabled} required label="Responsável" placeholder="Selecione um responsável" options={choices} />
      {data ? <TaskPages {...data} change={setPage} label="Páginas de responsáveis" /> : <LoadChoices error={result?.error} reload={reload} />}
    </div>
  );
}
