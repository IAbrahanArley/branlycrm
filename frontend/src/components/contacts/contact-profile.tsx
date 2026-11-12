"use client";

import { TaskWorkspace } from "@/components/tasks/task-workspace";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { Archive, Pencil, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { PanelState } from "@/components/panel/panel-state";
import { useClinic } from "@/components/panel/clinic-context";
import {
  contactPath, createTag, getConsents, getContact, isContactId, listTags, saveConsent, setArchived,
  setContactTag, withContactClinic, type ConsentChannel, type ConsentHistory, type ConsentStatus, type Contact,
} from "@/lib/api/contacts";
import {
  asContactError, clinicNeedsRefresh, ContactHeading, ContactLink, ContactProblem, contactInput,
  mutationResultUncertain, PatientBadge, useContactResource,
} from "./contact-ui";

const channels = { WHATSAPP: "WhatsApp", EMAIL: "E-mail" } as const;
const statuses = { OPT_IN: "Autorizado", OPT_OUT: "Recusado", NONE: "Sem registro" } as const;
const sources: Record<string, string> = { MANUAL: "Registro manual", IMPORT: "Importação", WHATSAPP: "WhatsApp" };
const dateOnly = new Intl.DateTimeFormat("pt-BR", { dateStyle: "medium", timeZone: "UTC" });
const dateTime = new Intl.DateTimeFormat("pt-BR", { dateStyle: "short", timeStyle: "short", timeZone: "UTC" });

type Run = (action: () => Promise<unknown>, message: string) => Promise<void>;

function TagEditor({ contact, pending, run }: { contact: Contact; pending: boolean; run: Run }) {
  const { result, reload } = useContactResource(listTags);
  const [selected, setSelected] = useState("");
  const [name, setName] = useState("");
  const available = result?.data?.filter((tag) => !contact.tags.some((attached) => attached.id === tag.id));

  return (
    <div className="space-y-4 border-t border-border pt-4">
      {!result && <p role="status" className="text-sm text-slate-600">Carregando etiquetas disponíveis…</p>}
      {result?.error && <ContactProblem error={result.error} onRetry={reload} />}
      {available && (
        <form method="post" className="flex flex-wrap items-end gap-2" onSubmit={(event) => {
          event.preventDefault();
          if (available.some((tag) => tag.id === selected)) void run(() => setContactTag(contact.id, selected, true), "Etiqueta adicionada.");
        }}>
          <div className="min-w-44 flex-1 space-y-1">
            <label htmlFor="contact-tag" className="text-sm font-semibold">Etiqueta disponível</label>
            <select id="contact-tag" className={contactInput} value={selected} onChange={(event) => setSelected(event.target.value)} disabled={pending || available.length === 0} required>
              <option value="">{available.length ? "Selecione uma etiqueta" : "Nenhuma etiqueta disponível"}</option>
              {available.map((tag) => <option key={tag.id} value={tag.id}>{tag.name}</option>)}
            </select>
          </div>
          <Button type="submit" variant="outline" className="min-h-11" disabled={pending || !selected}>Adicionar</Button>
        </form>
      )}
      <form method="post" className="flex flex-wrap items-end gap-2" onSubmit={(event) => {
        event.preventDefault();
        if (name.trim()) void run(() => createTag(name.trim()), "Etiqueta criada no catálogo. Selecione-a para adicionar ao contato.");
      }}>
        <div className="min-w-44 flex-1 space-y-1">
          <label htmlFor="new-contact-tag" className="text-sm font-semibold">Nova etiqueta no catálogo</label>
          <input id="new-contact-tag" name="tagName" autoComplete="off" className={contactInput} value={name} onChange={(event) => setName(event.target.value)} maxLength={50} required disabled={pending} />
        </div>
        <Button type="submit" variant="outline" className="min-h-11" disabled={pending || !name.trim()}>Criar etiqueta</Button>
      </form>
    </div>
  );
}

function ConsentCard({ contact, consents, canWrite, pending, run }: {
  contact: Contact; consents: ConsentHistory; canWrite: boolean; pending: boolean; run: Run;
}) {
  const [channel, setChannel] = useState<ConsentChannel | "">("");
  const [status, setStatus] = useState<ConsentStatus | "">("");

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (channel && status) void run(() => saveConsent(contact.id, channel, status), "Preferência de comunicação registrada.");
  }

  return (
    <Card aria-labelledby="consent-title">
      <CardHeader>
        <CardTitle id="consent-title">Consentimento de comunicação</CardTitle>
        <CardDescription>Preferências registradas por canal.</CardDescription>
      </CardHeader>
      <CardContent className="space-y-6">
        <dl className="grid gap-2 sm:grid-cols-2">
          {(Object.keys(channels) as ConsentChannel[]).map((key) => (
            <div key={key} className="rounded-lg border border-border px-4 py-3">
              <dt className="text-sm text-slate-600">{channels[key]}</dt>
              <dd className="mt-1 font-semibold">{statuses[consents.current[key]]}</dd>
            </div>
          ))}
        </dl>
        {canWrite && (
          <form method="post" onSubmit={submit} className="space-y-3 border-t border-border pt-4">
            <p className="text-sm text-slate-600">Registre a preferência que a pessoa informou.</p>
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1">
                <label htmlFor="consent-channel" className="text-sm font-semibold">Canal</label>
                <select id="consent-channel" className={contactInput} value={channel} onChange={(event) => setChannel(event.target.value as ConsentChannel | "")} disabled={pending} required>
                  <option value="">Selecione o canal</option>
                  <option value="WHATSAPP">WhatsApp</option><option value="EMAIL">E-mail</option>
                </select>
              </div>
              <div className="space-y-1">
                <label htmlFor="consent-status" className="text-sm font-semibold">Preferência</label>
                <select id="consent-status" className={contactInput} value={status} onChange={(event) => setStatus(event.target.value as ConsentStatus | "")} disabled={pending} required>
                  <option value="">Selecione a preferência</option>
                  <option value="OPT_IN">Autorizado</option><option value="OPT_OUT">Recusado</option>
                </select>
              </div>
            </div>
            <Button type="submit" className="min-h-11" disabled={pending || !channel || !status}>Registrar preferência</Button>
          </form>
        )}
        <div className="space-y-2 border-t border-border pt-4">
          <h3 className="text-sm font-semibold">Histórico de consentimento</h3>
          {consents.history.length === 0 ? <p className="text-sm text-slate-600">Nenhuma preferência registrada.</p> : (
            <ul className="divide-y divide-border">
              {consents.history.map((entry) => (
                <li key={entry.id} className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-3 text-sm">
                  <div className="min-w-0 space-y-1">
                    <p className="font-semibold">{channels[entry.channel]} · {statuses[entry.status]}</p>
                    <p className="text-slate-600 wrap-anywhere">{sources[entry.source] ?? entry.source}{entry.recordedBy && ` · ID do autor: ${entry.recordedBy}`}</p>
                  </div>
                  <time className="text-slate-600 tabular-nums" dateTime={entry.recordedAt}>{dateTime.format(new Date(entry.recordedAt))} UTC</time>
                </li>
              ))}
            </ul>
          )}
        </div>
      </CardContent>
    </Card>
  );
}

function ProfileDetails({ contact, consents, onRefresh }: {
  contact: Contact; consents: ConsentHistory; onRefresh: (message?: string) => void;
}) {
  const { clinic, refresh } = useClinic();
  const [confirmArchive, setConfirmArchive] = useState(false);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<{ message: string; uncertain: boolean }>();
  const busy = useRef(false);
  const alive = useRef(false);
  const archiveAction = useRef<HTMLButtonElement>(null);
  const cancelArchive = useRef<HTMLButtonElement>(null);
  const archived = Boolean(contact.archivedAt);
  const canWrite = clinic.permissions.CONTACT_WRITE === "ALL";
  const canArchive = clinic.permissions.CONTACT_ARCHIVE === "ALL";

  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; };
  }, []);

  useEffect(() => {
    if (confirmArchive) cancelArchive.current?.focus();
  }, [confirmArchive]);

  const run: Run = async (action, message) => {
    if (busy.current || failure?.uncertain) return;
    busy.current = true;
    setPending(true);
    setFailure(undefined);
    try {
      await withContactClinic(clinic.tenantId, action);
      if (alive.current) onRefresh(message);
    } catch (cause) {
      if (!alive.current) return;
      const error = asContactError(cause);
      if (clinicNeedsRefresh(error)) refresh();
      else if (error.code === "CONTACT_ARCHIVED") onRefresh("O contato foi arquivado em outra sessão. O perfil foi atualizado.");
      else setFailure({ message: error.message, uncertain: mutationResultUncertain(error) });
    } finally {
      busy.current = false;
      if (alive.current) setPending(false);
    }
  };

  const locked = pending || Boolean(failure?.uncertain);
  const profileFields = [
    ["Telefone", contact.phone],
    ["E-mail", contact.email],
    ["Nascimento", contact.birthDate && dateOnly.format(new Date(contact.birthDate))],
    ["Cadastro", `${dateTime.format(new Date(contact.createdAt))} UTC`],
  ];

  return (
    <>
      <ContactHeading title={contact.name} description={archived ? "Contato arquivado" : "Contato ativo"} back
        action={canWrite && !archived && <ContactLink href={`${contactPath(contact.id)}/editar`}><Pencil size={16} aria-hidden="true" />Editar contato</ContactLink>} />
      {contact.patient && <PatientBadge />}
      {pending && <p role="status" className="text-sm text-slate-600">Salvando alteração…</p>}
      {failure && (
        <div className="space-y-2 rounded-lg border border-red-300 bg-red-50 p-4 text-sm text-red-900">
          <p role="alert">{failure.uncertain ? "Não foi possível confirmar o resultado. Atualize o perfil e confira os dados antes de repetir a ação." : failure.message}</p>
          {failure.uncertain && <Button variant="outline" className="min-h-11" onClick={() => onRefresh()}>Atualizar perfil</Button>}
        </div>
      )}
      <div className="grid items-start gap-4 lg:grid-cols-2">
        <Card aria-labelledby="contact-data">
          <CardHeader><CardTitle id="contact-data">Dados do contato</CardTitle></CardHeader>
          <CardContent className="space-y-5">
            <dl className="grid gap-4 sm:grid-cols-2">
              {profileFields.map(([label, value]) => (
                <div key={label} className="min-w-0">
                  <dt className="text-sm text-slate-600">{label}</dt>
                  <dd className="mt-1 text-sm font-medium wrap-anywhere">{value || "Não informado"}</dd>
                </div>
              ))}
            </dl>
            <div className="space-y-1 border-t border-border pt-4">
              <h3 className="text-sm font-semibold">Observações</h3>
              <p className="text-sm text-slate-700 wrap-anywhere whitespace-pre-wrap">{contact.notes || "Sem observações."}</p>
            </div>
            {canArchive && (
              <div className="space-y-3 border-t border-border pt-4">
                <Button ref={archiveAction} variant="outline" className="min-h-11 gap-2" disabled={locked} onClick={() => setConfirmArchive(true)}>
                  <Archive size={16} aria-hidden="true" />{archived ? "Restaurar contato" : "Arquivar contato"}
                </Button>
                {confirmArchive && (
                  <div className="space-y-3 rounded-lg border border-border bg-slate-50 p-3">
                    <p className="text-sm text-slate-700">{archived ? "O contato voltará à lista principal." : "O contato ficará disponível no filtro de arquivados."}</p>
                    <div className="flex flex-wrap gap-2">
                      <Button ref={cancelArchive} variant="outline" className="min-h-11" disabled={locked} onClick={() => { setConfirmArchive(false); archiveAction.current?.focus(); }}>Cancelar</Button>
                      <Button className="min-h-11" disabled={locked} onClick={() => void run(() => setArchived(contact.id, !archived), archived ? "Contato restaurado." : "Contato arquivado.")}>
                        {archived ? "Confirmar restauração" : "Confirmar arquivamento"}
                      </Button>
                    </div>
                  </div>
                )}
              </div>
            )}
          </CardContent>
        </Card>
        <Card aria-labelledby="contact-tags">
          <CardHeader><CardTitle id="contact-tags">Etiquetas</CardTitle></CardHeader>
          <CardContent className="space-y-4">
            {contact.tags.length === 0 ? <p className="text-sm text-slate-600">Nenhuma etiqueta vinculada.</p> : (
              <ul className="flex flex-wrap gap-2">
                {contact.tags.map((tag) => (
                  <li key={tag.id} className="inline-flex max-w-full items-center gap-1 rounded-lg bg-slate-100 px-2 py-1 text-sm wrap-anywhere">
                    <span>{tag.name}</span>
                    {canWrite && !archived && <Button variant="ghost" size="icon" className="size-11 shrink-0" aria-label={`Remover etiqueta ${tag.name}`} disabled={locked} onClick={() => void run(() => setContactTag(contact.id, tag.id, false), "Etiqueta removida.")}><X size={15} aria-hidden="true" /></Button>}
                  </li>
                ))}
              </ul>
            )}
            {canWrite && !archived && <TagEditor contact={contact} pending={locked} run={run} />}
          </CardContent>
        </Card>
      </div>
      <ConsentCard contact={contact} consents={consents} canWrite={canWrite} pending={locked} run={run} />
      {clinic.permissions.TASK_READ === "ALL" && <TaskWorkspace binding={{ contactId: contact.id, contactName: contact.name, archived }} />}
    </>
  );
}

function Profile({ id }: { id: string }) {
  const load = useCallback((signal: AbortSignal) => Promise.all([getContact(id, signal), getConsents(id, signal)]), [id]);
  const { result, reload } = useContactResource(load);
  const [message, setMessage] = useState("");
  return (
    <>
      <p role="status" className={message ? "rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-900" : "sr-only"}>{message}</p>
      {result?.data ? (
        <ProfileDetails contact={result.data[0]} consents={result.data[1]} onRefresh={(notice) => { setMessage(notice ?? ""); reload(); }} />
      ) : (
        <>
          <ContactHeading title="Perfil do contato" back />
          {result?.error ? <ContactProblem error={result.error} onRetry={reload} /> : <PanelState kind="loading" title="Carregando perfil…" />}
        </>
      )}
    </>
  );
}

export function ContactProfile({ id }: { id: string }) {
  if (isContactId(id)) return <Profile key={id} id={id} />;
  return (
    <>
      <ContactHeading title="Perfil do contato" back />
      <PanelState kind="error" title="Contato não encontrado" message="Volte à lista para acessar os contatos desta clínica." />
    </>
  );
}
