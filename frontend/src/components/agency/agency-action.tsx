"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { Dialog } from "@base-ui/react/dialog";
import { Button } from "@/components/ui/button";
import { createPortfolioClinic, reissueOwnerInvitation, type ClinicDraft, type PortfolioClinic } from "@/lib/api/agencies";
import { agencyErrorMessage, ownerDeliveryMessage } from "@/lib/api/agency-feedback";
import { getIdentity } from "@/lib/api/auth";
import { ApiError } from "@/lib/api/client";
import { loginRetryDeadline } from "@/lib/api/retry-after";
import { useAgencies } from "./agency-context";

export type AgencyAction = { kind: "create"; requestId: string } | { kind: "reissue"; clinic: PortfolioClinic };
export const agencyInput = "min-h-11 w-full rounded-lg border border-slate-400 bg-white px-3 py-2 text-base text-slate-950 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary";

export async function confirmAgencyAccount(subjectId: string, signal?: AbortSignal) {
  const identity = await getIdentity(signal);
  if (identity.subjectId !== subjectId) {
    throw new ApiError(409, "SESSION_CONTEXT_CHANGED", "A conta da sessão mudou. Abra a carteira novamente.");
  }
}

export function mustRefreshAgency(cause: unknown) {
  return cause instanceof ApiError && ((cause.status === 403 && cause.code !== "CSRF_INVALID") ||
    ["AGENCY_NOT_FOUND", "SESSION_CONTEXT_CHANGED"].includes(cause.code));
}

export function AgencyActionDialog({ agencyId, action, retryDraft, recovered, rememberRequest, setRetryDraft, blockedUntil, onLimit, onClose, onDone }: {
  agencyId: string;
  action: AgencyAction;
  retryDraft?: ClinicDraft;
  recovered: boolean;
  rememberRequest: (requestId?: string) => boolean;
  setRetryDraft: (draft?: ClinicDraft) => void;
  blockedUntil: number;
  onLimit: (deadline: number) => void;
  onClose: () => void;
  onDone: (notice: string, refreshAccess?: boolean) => void;
}) {
  const { subjectId } = useAgencies();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [checkList, setCheckList] = useState(false);
  const busy = useRef(false);
  const alive = useRef(false);
  const creating = action.kind === "create";

  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; };
  }, []);

  function dismiss() {
    if (busy.current) return;
    if (checkList) onDone("");
    else onClose();
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy.current || checkList || (!creating && Date.now() < blockedUntil)) return;
    const form = new FormData(event.currentTarget);
    const ownerEmail = String(form.get("ownerEmail") ?? "").trim();
    const draft = action.kind === "create" ? retryDraft ?? {
      clinicName: String(form.get("clinicName") ?? "").trim(), ownerEmail, requestId: action.requestId,
    } : undefined;
    if (draft && !draft.clinicName) {
      setError("Informe o nome da clínica.");
      return;
    }
    busy.current = true;
    setPending(true);
    setError("");
    let sent = false;
    try {
      await confirmAgencyAccount(subjectId);
      if (!alive.current) return;
      if (draft && !rememberRequest(draft.requestId)) {
        setError("Permita o armazenamento temporário desta aba para que seja possível recuperar o cadastro se a conexão cair.");
        return;
      }
      sent = true;
      let notice: string;
      if (action.kind === "reissue") {
        const result = await reissueOwnerInvitation(agencyId, action.clinic.tenantId, ownerEmail);
        notice = ownerDeliveryMessage(result.delivery);
      } else if (draft) {
        const result = await createPortfolioClinic(agencyId, draft);
        notice = result.created ? `${result.clinicName} foi cadastrada. ${ownerDeliveryMessage(result.delivery)}` : ownerDeliveryMessage("NOT_SENT");
      } else return;
      await confirmAgencyAccount(subjectId);
      if (!alive.current) return;
      if (draft) {
        rememberRequest(undefined);
        setRetryDraft(undefined);
      }
      onDone(notice);
    } catch (cause) {
      if (!alive.current) return;
      if (mustRefreshAgency(cause)) {
        onDone(cause instanceof ApiError ? agencyErrorMessage(cause.code, cause.message) : "", true);
        return;
      }
      const uncertain = sent && (!(cause instanceof ApiError) || cause.status === 0 || cause.status >= 500 || (cause.status >= 200 && cause.status < 300));
      if (uncertain) {
        if (draft) {
          setRetryDraft(draft);
          setError("Não foi possível confirmar o cadastro. Tente novamente com os mesmos dados para evitar duplicação.");
        } else {
          setCheckList(true);
          setError("Não foi possível confirmar a reemissão. Confira com o destinatário se ele recebeu o e-mail antes de repetir.");
        }
      } else if (cause instanceof ApiError) {
        if (cause.status === 429 && cause.retryAfter) onLimit(loginRetryDeadline(cause.retryAfter, Date.now()));
        if (["IDEMPOTENCY_KEY_REUSED", "AGENCY_CLINIC_HAS_OWNER", "CLINIC_NOT_FOUND"].includes(cause.code)) {
          if (draft) {
            rememberRequest(undefined);
            setRetryDraft(undefined);
          }
          setCheckList(true);
        } else if (draft && sent && !recovered) {
          rememberRequest(undefined);
        }
        setError(agencyErrorMessage(cause.code, cause.message));
      } else setError("Não foi possível verificar a sessão. Tente novamente.");
    } finally {
      busy.current = false;
      if (alive.current) setPending(false);
    }
  }

  return (
    <Dialog.Root open onOpenChange={(open) => { if (!open) dismiss(); }}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-slate-950/55" />
        <Dialog.Popup className="fixed top-1/2 left-1/2 z-50 max-h-[90dvh] w-[calc(100%-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2 space-y-4 overflow-y-auto overscroll-contain rounded-xl border border-border bg-card p-5 shadow-xl sm:p-6">
          <Dialog.Title className="text-xl font-bold">{creating ? "Cadastrar clínica" : "Reemitir convite do gestor"}</Dialog.Title>
          <Dialog.Description className="text-sm text-slate-600 wrap-anywhere">
            {action.kind === "create"
              ? "A clínica entra em preparação e o primeiro gestor recebe um convite por e-mail."
              : `${action.clinic.clinicName} · Confirme o endereço de destino. A reemissão substitui o link anterior.`}
          </Dialog.Description>
          <form method="post" onSubmit={submit} className="space-y-4">
            {creating && <label className="block space-y-1 text-sm font-medium">Nome da clínica
              <input name="clinicName" autoComplete="off" maxLength={160} required defaultValue={retryDraft?.clinicName}
                readOnly={Boolean(retryDraft)} disabled={pending || checkList} className={agencyInput} />
            </label>}
            <label className="block space-y-1 text-sm font-medium">E-mail do gestor
              <input name="ownerEmail" type="email" autoComplete="off" spellCheck={false} maxLength={254} required
                defaultValue={retryDraft?.ownerEmail} readOnly={Boolean(retryDraft)} disabled={pending || checkList} className={agencyInput} />
            </label>
            {error && <p role="alert" className="rounded-lg bg-red-50 p-3 text-sm text-red-900">{error}</p>}
            {recovered && !retryDraft && !pending && !checkList && (
              <p className="text-sm text-slate-700">Preencha o mesmo nome e e-mail usados na tentativa anterior. A solicitação mantém o identificador original.</p>
            )}
            {!creating && blockedUntil > 0 && <p role="status" className="text-sm text-slate-700">O reenvio será liberado no prazo informado pelo servidor.</p>}
            <div className="flex flex-wrap justify-end gap-2">
              <Dialog.Close render={<Button type="button" variant="outline" className="min-h-11" disabled={pending} />}>{recovered ? "Voltar à carteira" : "Cancelar"}</Dialog.Close>
              {checkList
                ? <Button type="button" className="min-h-11" onClick={() => onDone("")}>Atualizar carteira</Button>
                : <Button type="submit" className="min-h-11" disabled={pending || (!creating && blockedUntil > 0)}>
                    {pending ? "Enviando…" : recovered ? "Repetir mesma solicitação" : creating ? "Cadastrar clínica" : "Enviar novo convite"}
                  </Button>}
            </div>
          </form>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
