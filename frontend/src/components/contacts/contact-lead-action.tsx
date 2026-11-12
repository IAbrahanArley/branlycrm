"use client";

import { useCallback, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Plus } from "lucide-react";
import { useClinicResource } from "@/components/panel/clinic-resource";
import { useTabVisible } from "@/components/panel/use-tab-visible";
import { Button, buttonVariants } from "@/components/ui/button";
import { OriginDialog, useOriginMutation } from "@/components/funnel/origin-dialog";
import { isContactId } from "@/lib/api/contacts";
import { listLeads } from "@/lib/api/leads";

type Props = { contactId: string; contactName: string };

export function ContactLeadAction(props: Props) {
  const router = useRouter();
  const mutation = useOriginMutation();
  const visible = useTabVisible();
  return visible ? <ContactLeadContent {...props} mutation={mutation} saved={(id) => router.push(`/app/funil/${id}`)} /> : null;
}

function ContactLeadContent({ contactId, contactName, mutation, saved }: Props & {
  mutation: ReturnType<typeof useOriginMutation>; saved: (leadId: string) => void;
}) {
  const [editing, setEditing] = useState(false);
  const load = useCallback(async (signal: AbortSignal) => {
    const page = await listLeads({ contactId, status: "OPEN", page: 1 }, signal);
    const lead = page.items.find((item) => item.status === "OPEN" && item.contactId === contactId && isContactId(item.id));
    return lead?.id ?? null;
  }, [contactId]);
  const { result, reload } = useClinicResource(load);
  const leadId = result?.data;

  if (leadId) {
    return <Link href={`/app/funil/${leadId}`} className={buttonVariants({ variant: "outline", className: "min-h-11" })}>Abrir lead em andamento</Link>;
  }
  if (!result) return <p role="status" className="self-center text-sm text-muted-foreground">Verificando lead…</p>;
  if (result.error) {
    return <Button type="button" variant="outline" className="min-h-11" onClick={reload}>Tentar consultar lead novamente</Button>;
  }
  function refresh() {
    setEditing(false);
    mutation.clearFailure();
    reload();
  }
  if (!editing && mutation.failure?.needsReload) {
    return <div className="space-y-1">
      <p role="alert" className="text-sm text-destructive">{mutation.failure.message}</p>
      <Button type="button" variant="outline" className="min-h-11" onClick={refresh}>Atualizar lead do contato</Button>
    </div>;
  }
  return (
    <>
      <Button type="button" className="min-h-11" disabled={mutation.pending}
        onClick={() => { mutation.clearFailure(); setEditing(true); }}><Plus aria-hidden="true" />Novo lead</Button>
      {!editing && mutation.pending && <p role="status" className="self-center text-sm text-muted-foreground">Criando lead…</p>}
      {!editing && mutation.failure && <p role="alert" className="self-center text-sm text-destructive">{mutation.failure.message}</p>}
      {editing && <OriginDialog action={{ kind: "create", contactId, contactName }} mutation={mutation}
        close={() => { mutation.clearFailure(); setEditing(false); }} reload={refresh}
        saved={(lead) => {
          setEditing(false);
          reload();
          saved(lead.id);
        }} />}
    </>
  );
}
