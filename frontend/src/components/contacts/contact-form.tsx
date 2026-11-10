"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { PanelState } from "@/components/panel/panel-state";
import { useClinic } from "@/components/panel/clinic-context";
import {
  checkPhone, contactPath, getContact, isContactId, saveContact, withContactClinic, type Contact, type ContactDraft,
} from "@/lib/api/contacts";
import {
  asContactError, clinicNeedsRefresh, ContactHeading, ContactLink, ContactProblem, contactInput,
  mutationResultUncertain, useContactResource,
} from "@/components/contacts/contact-ui";
import type { ApiError } from "@/lib/api/client";

const fields = [
  { key: "name", label: "Nome", autoComplete: "name", limit: 150, required: true, type: "text" },
  { key: "phone", label: "Telefone", autoComplete: "off", limit: 32, required: true, type: "tel" },
  { key: "email", label: "E-mail", autoComplete: "off", limit: 254, required: false, type: "email" },
  { key: "birthDate", label: "Data de nascimento", autoComplete: "off", limit: undefined, required: false, type: "date" },
] as const;

function values(contact?: Contact): ContactDraft {
  return {
    name: contact?.name ?? "", phone: contact?.phone ?? "", email: contact?.email ?? "",
    birthDate: contact?.birthDate ?? "", notes: contact?.notes ?? "",
  };
}

function Editor({ original }: { original?: Contact }) {
  const router = useRouter();
  const { clinic, refresh } = useClinic();
  const [base, setBase] = useState(original);
  const [draft, setDraft] = useState(() => values(original));
  const [pending, setPending] = useState<"save" | "check" | "latest">();
  const [error, setError] = useState<ApiError>();
  const [latest, setLatest] = useState<Contact>();
  const [latestError, setLatestError] = useState("");
  const [uncertain, setUncertain] = useState(false);
  const [phoneResult, setPhoneResult] = useState<{ id: string | null }>();
  const form = useRef<HTMLFormElement>(null);
  const alive = useRef(false);
  const busy = useRef(false);
  const readSignal = useRef<AbortController>(null);
  const dirty = JSON.stringify(values(base)) !== JSON.stringify(draft);

  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; readSignal.current?.abort(); };
  }, []);

  useEffect(() => {
    if (!dirty) return;
    function confirmLeave(event: BeforeUnloadEvent) {
      event.preventDefault();
      event.returnValue = true;
    }
    window.addEventListener("beforeunload", confirmLeave);
    return () => window.removeEventListener("beforeunload", confirmLeave);
  }, [dirty]);

  useEffect(() => {
    if (pending || !error) return;
    const field = error.fields[0] ?? (error.code === "CONTACT_DUPLICATE_PHONE" ? "phone" : "");
    const input = form.current?.elements.namedItem(field);
    if (input instanceof HTMLElement) input.focus();
  }, [error, pending]);

  function report(cause: unknown, saving = false) {
    if (!alive.current) return;
    const issue = asContactError(cause);
    if (clinicNeedsRefresh(issue)) {
      refresh();
      return;
    }
    if (saving && mutationResultUncertain(issue)) setUncertain(true);
    setError(issue);
  }

  async function fetchLatest() {
    if (!base || busy.current) return;
    busy.current = true;
    setPending("latest");
    const controller = new AbortController();
    readSignal.current = controller;
    try {
      const next = await withContactClinic(clinic.tenantId, () => getContact(base.id, controller.signal), controller.signal);
      if (alive.current) setLatest(next);
    } catch (cause) {
      if (!controller.signal.aborted) {
        const issue = asContactError(cause);
        if (clinicNeedsRefresh(issue)) refresh();
        else if (alive.current) setLatestError(issue.message);
      }
    } finally {
      busy.current = false;
      if (alive.current) setPending(undefined);
    }
  }

  function applyLatest() {
    if (!latest || latest.archivedAt || busy.current) return;
    const oldValues = values(base);
    const newValues = values(latest);
    const merged = { ...draft };
    for (const key of Object.keys(draft) as (keyof ContactDraft)[]) {
      if (draft[key] === oldValues[key]) merged[key] = newValues[key];
    }
    setDraft(merged);
    setBase(latest);
    setLatest(undefined);
    setLatestError("");
    setPhoneResult(undefined);
    setError(undefined);
  }

  async function verifyPhone() {
    if (busy.current || !draft.phone.trim()) return;
    busy.current = true;
    setPending("check");
    setError(undefined);
    setPhoneResult(undefined);
    const controller = new AbortController();
    readSignal.current = controller;
    try {
      const result = await withContactClinic(clinic.tenantId, () => checkPhone(draft.phone, controller.signal), controller.signal);
      if (alive.current) setPhoneResult({ id: isContactId(result.contact?.id) ? result.contact.id : null });
    } catch (cause) {
      if (!controller.signal.aborted) report(cause);
    } finally {
      busy.current = false;
      if (alive.current) setPending(undefined);
    }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy.current || uncertain || error?.code === "CONTACT_STALE" || error?.code === "CONTACT_ARCHIVED") return;
    busy.current = true;
    setPending("save");
    setError(undefined);
    setPhoneResult(undefined);
    try {
      const saved = await withContactClinic(clinic.tenantId, () => saveContact(draft, base));
      if (!isContactId(saved?.id)) throw new Error("ID inválido");
      if (alive.current) router.replace(contactPath(saved.id));
    } catch (cause) {
      report(cause, true);
    } finally {
      busy.current = false;
      if (alive.current) setPending(undefined);
    }
  }

  function change(key: keyof ContactDraft, value: string) {
    if (key === "phone") setPhoneResult(undefined);
    setDraft((previous) => ({ ...previous, [key]: value }));
  }

  const duplicateId = isContactId(error?.contactId) ? error.contactId : phoneResult?.id;
  const stale = error?.code === "CONTACT_STALE";
  const archived = Boolean(base?.archivedAt || latest?.archivedAt || error?.code === "CONTACT_ARCHIVED");
  const currentValues = values(latest);
  const baseValues = values(base);
  const changes = [...fields.map(({ key, label }) => ({ key, label })), { key: "notes" as const, label: "Observações" }]
    .filter(({ key }) => currentValues[key] !== baseValues[key]);
  const invalid = (name: keyof ContactDraft) => Boolean(error?.fields.includes(name) || (name === "phone" && error?.code === "CONTACT_DUPLICATE_PHONE"));

  return (
    <>
      <ContactHeading title={original ? "Editar contato" : "Novo contato"} description="Nome e telefone são obrigatórios. Preencha os demais campos quando forem úteis." back />
      <Card aria-label="Dados do contato" className="max-w-3xl p-4 sm:p-6">
        <form ref={form} method="post" onSubmit={submit} autoComplete="off" className="space-y-5">
          <fieldset disabled={Boolean(pending) || archived || uncertain} className="grid gap-4 sm:grid-cols-2">
            <legend className="sr-only">Dados do contato</legend>
            {fields.map(({ key, label, limit, required, type, autoComplete }) => (
              <div key={key} className="space-y-1">
                <label htmlFor={`contact-${key}`} className="text-sm font-semibold">{label} {required && <span aria-hidden="true">*</span>}</label>
                <input id={`contact-${key}`} name={key} type={type} autoComplete={autoComplete === "name" ? "off" : autoComplete} spellCheck={key === "email" ? false : undefined}
                  value={draft[key]} onChange={(event) => change(key, event.target.value)} maxLength={limit} required={required}
                  aria-invalid={invalid(key)} aria-describedby={invalid(key) ? "contact-form-error" : key === "phone" ? "contact-phone-hint" : undefined}
                  className={contactInput} />
                {key === "phone" && <p id="contact-phone-hint" className="text-xs text-slate-600">Inclua o DDD. Para números internacionais, informe o código do país.</p>}
                {key === "phone" && !original && (
                  <Button type="button" variant="outline" disabled={!draft.phone.trim()} onClick={verifyPhone} className="mt-2 min-h-11">
                    {pending === "check" ? "Conferindo…" : "Conferir número"}
                  </Button>
                )}
              </div>
            ))}
            <div className="space-y-1 sm:col-span-2">
              <label htmlFor="contact-notes" className="text-sm font-semibold">Observações</label>
              <textarea id="contact-notes" name="notes" value={draft.notes} onChange={(event) => change("notes", event.target.value)} maxLength={2000} rows={3}
                aria-invalid={invalid("notes")} aria-describedby={invalid("notes") ? "contact-form-error contact-notes-hint" : "contact-notes-hint"} className={contactInput} />
              <p id="contact-notes-hint" className="text-xs text-slate-600">Use para observações de atendimento. Evite dados clínicos ou de saúde.</p>
            </div>
          </fieldset>
          {error && <p id="contact-form-error" role="alert" className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-900">{uncertain ? "Não foi possível confirmar se os dados foram salvos. Confira o contato antes de tentar novamente." : error.message}</p>}
          {phoneResult && <p role="status" className="text-sm text-slate-700">{phoneResult.id ? "Este telefone já está vinculado a um contato." : "Nenhum contato encontrado para este número."}</p>}
          {duplicateId && <ContactLink href={contactPath(duplicateId)}>Abrir contato existente</ContactLink>}
          {stale && !latest && (
            <div className="space-y-2">
              <p className="text-sm text-slate-700">Seu rascunho foi preservado. Consulte a versão atual para revisar as mudanças.</p>
              {latestError && <p role="alert" className="text-sm text-red-800">{latestError}</p>}
              <Button type="button" variant="outline" onClick={() => { setLatestError(""); void fetchLatest(); }} disabled={Boolean(pending)} className="min-h-11">Consultar versão atual</Button>
            </div>
          )}
          {latest && (
            <section aria-label="Versão atual" className="space-y-3 rounded-lg border border-amber-200 bg-amber-50 p-4">
              <h2 className="font-semibold">Revise a versão atual</h2>
              {changes.length > 0 ? <dl className="space-y-2">{changes.map(({ key, label }) => (
                <div key={key}><dt className="text-sm font-semibold">{label}</dt><dd className="text-sm whitespace-pre-wrap wrap-anywhere">{currentValues[key] || "Em branco"}</dd></div>
              ))}</dl> : <p className="text-sm">Os campos deste formulário estão iguais aos da versão anterior.</p>}
              {latest.archivedAt ? <p className="text-sm">O contato foi arquivado. Volte ao perfil para consultar a situação.</p> : (
                <Button type="button" variant="outline" onClick={applyLatest} className="min-h-11">Aplicar versão atual ao rascunho</Button>
              )}
            </section>
          )}
          <div className="flex flex-wrap gap-2">
            <Button type="submit" disabled={Boolean(pending) || stale || archived || uncertain} className="min-h-11">
              {pending === "save" ? "Salvando…" : original ? "Salvar alterações" : "Cadastrar contato"}
            </Button>
            <ContactLink href={original ? contactPath(original.id) : "/app/contatos"}>{uncertain ? (original ? "Conferir perfil" : "Conferir lista") : "Cancelar"}</ContactLink>
          </div>
        </form>
      </Card>
    </>
  );
}

function EditContact({ id }: { id: string }) {
  const load = useCallback((signal: AbortSignal) => getContact(id, signal), [id]);
  const { result, reload } = useContactResource(load);
  if (result?.data && !result.data.archivedAt) return <Editor original={result.data} />;
  return (
    <>
      <ContactHeading title="Editar contato" back />
      {!result && <PanelState kind="loading" title="Carregando dados do contato…" />}
      {result?.error && <ContactProblem error={result.error} onRetry={reload} />}
      {result?.data?.archivedAt && <PanelState kind="forbidden" title="Contato arquivado" message="Abra o perfil para consultar ou restaurar este contato." />}
    </>
  );
}

export function ContactForm({ id }: { id?: string }) {
  const { clinic } = useClinic();
  if (clinic.permissions.CONTACT_WRITE !== "ALL") {
    return <><ContactHeading title={id ? "Editar contato" : "Novo contato"} back /><PanelState kind="forbidden" title="Sem permissão para alterar contatos" /></>;
  }
  if (id && !isContactId(id)) return <><ContactHeading title="Editar contato" back /><PanelState kind="empty" title="Contato não encontrado" /></>;
  return id ? <EditContact key={id} id={id} /> : <Editor />;
}
