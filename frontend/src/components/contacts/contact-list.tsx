"use client";

import { useCallback, useState, type FormEvent } from "react";
import Link from "next/link";
import { ChevronLeft, ChevronRight, Plus, Search } from "lucide-react";
import { useClinic } from "@/components/panel/clinic-context";
import { PanelState } from "@/components/panel/panel-state";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { CONTACT_PAGE_SIZE, contactPath, listContacts, listTags, type ContactFilters, type ContactSummary } from "@/lib/api/contacts";
import { ContactHeading, ContactLink, ContactProblem, contactInput, useContactResource } from "@/components/contacts/contact-ui";

const initialFilters: ContactFilters = { query: "", tagId: "", includeArchived: false, page: 1 };
const rowLink = "inline-flex min-h-11 items-center rounded-sm font-semibold text-[#174578] hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 wrap-anywhere";

function ContactStatus({ archived }: { archived: boolean }) {
  return <span className="inline-flex rounded-full bg-slate-100 px-2.5 py-1 text-xs font-medium text-slate-700">{archived ? "Arquivado" : "Ativo"}</span>;
}

function ContactRows({ contacts }: { contacts: ContactSummary[] }) {
  return (
    <>
      <div className="hidden md:block">
        <table className="w-full table-fixed text-left text-sm">
          <caption className="sr-only">Lista de contatos da clínica</caption>
          <thead className="border-b border-slate-200 bg-slate-50 text-slate-700">
            <tr>
              <th scope="col" className="w-[32%] px-4 py-3 font-semibold">Nome</th>
              <th scope="col" className="w-[23%] px-4 py-3 font-semibold">Telefone</th>
              <th scope="col" className="px-4 py-3 font-semibold">E-mail</th>
              <th scope="col" className="w-[18%] px-4 py-3 font-semibold">Situação</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-200">
            {contacts.map((contact) => (
              <tr key={contact.id} className="hover:bg-slate-50">
                <th scope="row" className="px-4 py-1"><Link href={contactPath(contact.id)} prefetch={false} className={rowLink}>{contact.name}</Link></th>
                <td className="px-4 py-1 wrap-anywhere">{contact.phone}</td>
                <td className="px-4 py-1 wrap-anywhere">{contact.email ?? "—"}</td>
                <td className="px-4 py-1"><ContactStatus archived={Boolean(contact.archivedAt)} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <ul aria-label="Contatos da clínica" className="divide-y divide-slate-200 md:hidden">
        {contacts.map((contact) => (
          <li key={contact.id} className="flex items-start justify-between gap-3 px-4 py-3">
            <div className="min-w-0">
              <Link href={contactPath(contact.id)} prefetch={false} className={rowLink}>{contact.name}</Link>
              <p className="text-sm text-slate-600 wrap-anywhere">{contact.phone}</p>
              {contact.email && <p className="mt-1 text-sm text-slate-600 wrap-anywhere">{contact.email}</p>}
            </div>
            <ContactStatus archived={Boolean(contact.archivedAt)} />
          </li>
        ))}
      </ul>
    </>
  );
}

export function ContactList() {
  const { clinic } = useClinic();
  const canWrite = clinic.permissions.CONTACT_WRITE === "ALL";
  const [filters, setFilters] = useState(initialFilters);
  const [filterKey, setFilterKey] = useState(0);
  const [filterError, setFilterError] = useState("");
  const load = useCallback((signal: AbortSignal) => listContacts(filters, signal), [filters]);
  const { result, reload } = useContactResource(load);
  const tags = useContactResource(listTags);
  const page = result?.data;
  const filterActive = Boolean(filters.query || filters.tagId || filters.includeArchived);
  const end = Math.min(Math.max(1, Math.ceil((page?.total ?? 0) / CONTACT_PAGE_SIZE)), 401);
  const format = new Intl.NumberFormat("pt-BR");

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const query = String(data.get("query") ?? "").trim();
    if (query.length === 1) {
      setFilterError("Digite pelo menos dois caracteres para buscar.");
      event.currentTarget.querySelector<HTMLInputElement>("#contact-search")?.focus();
      return;
    }
    setFilterError("");
    setFilters({ query, tagId: String(data.get("tagId") ?? ""), includeArchived: data.has("archived"), page: 1 });
  }

  function clear() {
    setFilterError("");
    setFilters(initialFilters);
    setFilterKey((key) => key + 1);
  }

  return (
    <>
      <ContactHeading title="Contatos" description="Encontre e gerencie os contatos da clínica atual." action={canWrite && (
        <ContactLink href="/app/contatos/novo" primary><Plus size={17} aria-hidden="true" /> Novo contato</ContactLink>
      )} />
      <Card aria-label="Busca e filtros" className="p-4 sm:p-5">
        <form key={filterKey} method="post" onSubmit={submit} className="flex flex-wrap items-end gap-x-4 gap-y-3">
          <label htmlFor="contact-search" className="min-w-0 flex-[1_0_100%] space-y-1 text-sm font-semibold sm:flex-[2_1_14rem]">
            <span>Buscar por nome, telefone ou e-mail</span>
            <input id="contact-search" type="search" name="query" maxLength={100} autoComplete="off" spellCheck={false}
              className={contactInput} aria-invalid={Boolean(filterError)} aria-describedby={filterError ? "contact-search-error" : undefined} />
          </label>
          <label className="min-w-0 flex-[1_1_10rem] space-y-1 text-sm font-semibold">
            <span>Etiqueta</span>
            <select name="tagId" className={contactInput} disabled={!tags.result?.data}>
              <option value="">Todas as etiquetas</option>
              {tags.result?.data?.map((tag) => <option key={tag.id} value={tag.id}>{tag.name}</option>)}
            </select>
          </label>
          <label className="flex min-h-11 items-center gap-2 text-sm font-medium">
            <input type="checkbox" name="archived" className="size-4 accent-[#174578]" /> Incluir arquivados
          </label>
          <Button type="submit" className="min-h-11 gap-2"><Search size={16} aria-hidden="true" /> Buscar</Button>
          {filterActive && <Button type="button" variant="outline" className="min-h-11" onClick={clear}>Limpar filtros</Button>}
        </form>
        {filterError && <p id="contact-search-error" role="alert" className="mt-2 text-sm text-red-800">{filterError}</p>}
        {tags.result?.error && (
          <div className="mt-2 flex items-center gap-2 text-sm text-red-800">
            <p role="alert">Não foi possível carregar as etiquetas.</p>
            <Button type="button" variant="outline" className="min-h-11" onClick={tags.reload}>Tentar novamente</Button>
          </div>
        )}
      </Card>
      {!result && <PanelState kind="loading" title="Carregando contatos…" />}
      {result?.error && <ContactProblem error={result.error} onRetry={reload} />}
      {page && page.items.length === 0 && (
        <PanelState kind="empty" title={filterActive ? "Nenhum contato encontrado" : filters.page === 1 ? "Nenhum contato cadastrado" : "Nenhum contato nesta página"}
          message={filterActive ? "Experimente outros termos ou remova os filtros." : "Os contatos cadastrados na clínica aparecem aqui."} />
      )}
      {page && page.items.length > 0 && <Card aria-label="Resultados da busca"><ContactRows contacts={page.items} /></Card>}
      {page && (end > 1 || filters.page > 1 || page.items.length > 0) && (
        <nav aria-label="Paginação de contatos" className="flex flex-wrap items-center justify-between gap-3">
          <p role="status" className="text-sm text-slate-600 tabular-nums">
            {format.format(page.total)} {page.total === 1 ? "contato" : "contatos"} · Página {format.format(page.page)} de {format.format(end)}
          </p>
          <div className="flex gap-2">
            <Button type="button" variant="outline" className="min-h-11" disabled={filters.page <= 1}
              onClick={() => setFilters({ ...filters, page: Math.max(1, Math.min(end, filters.page - 1)) })}><ChevronLeft size={16} aria-hidden="true" /> Anterior</Button>
            <Button type="button" variant="outline" className="min-h-11" disabled={filters.page >= end}
              onClick={() => setFilters({ ...filters, page: filters.page + 1 })}>Próxima <ChevronRight size={16} aria-hidden="true" /></Button>
          </div>
        </nav>
      )}
      {page && page.total > CONTACT_PAGE_SIZE * 401 && filters.page === end && <p className="text-sm text-slate-600">Use os filtros para encontrar outros contatos.</p>}
    </>
  );
}
