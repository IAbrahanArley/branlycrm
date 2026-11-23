"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Script from "next/script";
import { Dialog } from "@base-ui/react/dialog";
import { MessageCircle, RefreshCw } from "lucide-react";
import { useClinic } from "@/components/panel/clinic-context";
import { clinicApiError, clinicContextChanged } from "@/components/panel/clinic-resource";
import { useAgencies } from "@/components/agency/agency-context";
import { confirmAgencyAccount } from "@/components/agency/agency-action";
import { listAgencies } from "@/lib/api/agencies";
import { withSelectedClinic } from "@/lib/api/auth";
import { ApiError } from "@/lib/api/client";
import { loginRetryDeadline } from "@/lib/api/retry-after";
import { channelError, channelFailure, connectChannel, disconnectChannel, getChannel, type Channel, type ChannelTarget } from "@/lib/api/whatsapp";
import { beginMetaSignup } from "@/lib/meta-signup";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

type Guard = <T>(operation: () => Promise<T>, signal?: AbortSignal) => Promise<T>;
const statusLabels = {
  NONE: "Nenhum número conectado", CONNECTING: "Conectando", ACTIVE: "Conectado",
  FAILED: "Falha na conexão", DISCONNECTING: "Desconectando",
};
const appId = process.env.NEXT_PUBLIC_META_APP_ID ?? "";
const configId = process.env.NEXT_PUBLIC_META_SIGNUP_CONFIG_ID ?? "";
const configured = /^\d+$/.test(appId) && /^\d+$/.test(configId);

export function ClinicWhatsapp() {
  const { clinic, subjectId, refresh } = useClinic();
  const guard: Guard = useCallback((operation, signal) =>
    withSelectedClinic(clinic.tenantId, operation, signal, subjectId), [clinic.tenantId, subjectId]);
  if (clinic.permissions.CHANNEL_VIEW !== "ALL") return null;
  return <ChannelCard key={`${clinic.tenantId}:${subjectId}`} target={{ tenantId: clinic.tenantId }}
    canConnect={clinic.permissions.CHANNEL_CONNECT === "ALL"} guard={guard} refreshAccess={refresh} />;
}

export function AgencyWhatsapp({ agencyId, tenantId, clinicName }: { agencyId: string; tenantId: string; clinicName: string }) {
  const { subjectId, refresh } = useAgencies();
  const guard: Guard = useCallback(async (operation, signal) => {
    async function confirm() {
      await confirmAgencyAccount(subjectId, signal);
      const memberships = await listAgencies(signal ?? new AbortController().signal);
      if (!memberships.some((agency) => agency.agencyId === agencyId)) {
        throw new ApiError(403, "AGENCY_NOT_FOUND", "A carteira não está disponível.");
      }
    }
    await confirm();
    const result = await operation();
    await confirm();
    return result;
  }, [agencyId, subjectId]);
  return <ChannelCard key={`${agencyId}:${tenantId}:${subjectId}`} target={{ agencyId, tenantId }} canConnect
    clinicName={clinicName} guard={guard} refreshAccess={refresh} />;
}

function ChannelCard({ target, canConnect, guard, refreshAccess, clinicName }: {
  target: ChannelTarget; canConnect: boolean; guard: Guard; refreshAccess: () => void; clinicName?: string;
}) {
  const [channel, setChannel] = useState<Channel>();
  const [problem, setProblem] = useState("");
  const [notice, setNotice] = useState("");
  const [denied, setDenied] = useState(false);
  const [pending, setPending] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [sdkReady, setSdkReady] = useState(false);
  const [sdkFailed, setSdkFailed] = useState(false);
  const [revision, setRevision] = useState(0);
  const alive = useRef(false);
  const busy = useRef(false);
  const signup = useRef<AbortController | undefined>(undefined);
  const targetTenant = target.tenantId;
  const targetAgency = target.agencyId;
  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; signup.current?.abort(); };
  }, []);

  const refuse = useCallback((error: ApiError) => {
    if (["SESSION_CONTEXT_CHANGED", "CLINIC_NOT_SELECTED"].includes(error.code)) {
      setChannel(undefined);
      refreshAccess();
    } else if (error.status === 403 && error.code !== "CSRF_INVALID"
      || ["AGENCY_NOT_FOUND", "CLINIC_NOT_FOUND"].includes(error.code)) {
      setChannel(undefined);
      setDenied(true);
    } else if (clinicContextChanged(error)) refreshAccess();
    else setProblem(channelError(error));
  }, [refreshAccess]);

  useEffect(() => {
    let controller: AbortController | undefined;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let stopped = false;
    let retryAt = 0;
    let forbidden = false;
    async function load() {
      clearTimeout(timer);
      if (stopped || forbidden || document.visibilityState !== "visible" || controller) return;
      if (Date.now() < retryAt) { timer = setTimeout(load, Math.min(retryAt - Date.now(), 2_147_483_647)); return; }
      const request = new AbortController();
      controller = request;
      try {
        const data = await guard(() => getChannel({ tenantId: targetTenant, agencyId: targetAgency }, request.signal), request.signal);
        if (request.signal.aborted || stopped) return;
        setChannel(data);
        if (data.status === "CONNECTING" || data.status === "DISCONNECTING") timer = setTimeout(load, 5000);
      } catch (cause) {
        if (request.signal.aborted || stopped) return;
        const error = clinicApiError(cause);
        forbidden = error.status === 401 || error.status === 403 || error.status === 404;
        setChannel(undefined);
        refuse(error);
        if (error.retryAfter) retryAt = loginRetryDeadline(error.retryAfter, Date.now());
        if (!forbidden) timer = setTimeout(load, Math.max(5000, retryAt - Date.now()));
      } finally {
        if (controller === request) controller = undefined;
      }
    }
    function visibility() {
      clearTimeout(timer);
      if (document.visibilityState !== "visible") {
        controller?.abort();
        controller = undefined;
        setChannel(undefined);
        setConfirming(false);
      } else void load();
    }
    document.addEventListener("visibilitychange", visibility);
    void load();
    return () => {
      stopped = true;
      clearTimeout(timer);
      controller?.abort();
      document.removeEventListener("visibilitychange", visibility);
    };
  }, [guard, refuse, revision, targetAgency, targetTenant]);

  function initializeSdk() {
    try {
      if (!window.FB) { setSdkFailed(true); return; }
      window.FB.init({ appId, version: "v25.0", xfbml: false, cookie: false, autoLogAppEvents: false });
      setSdkFailed(false);
      setSdkReady(true);
    } catch { setSdkFailed(true); }
  }

  async function connect() {
    if (busy.current || !canConnect || channel?.status !== "NONE" || !sdkReady) return;
    busy.current = true;
    setPending(true);
    setProblem("");
    setNotice("");
    const controller = new AbortController();
    signup.current = controller;
    let sent = false;
    try {
      // Begin synchronously within the click so browsers allow the Meta popup.
      const code = await beginMetaSignup(configId, controller.signal);
      if (controller.signal.aborted || !alive.current) return;
      await guard(() => {
        if (controller.signal.aborted || !alive.current) throw new DOMException("Cancelled", "AbortError");
        sent = true;
        return connectChannel(target, code);
      }, controller.signal);
      if (alive.current) setNotice("Solicitação enviada. Aguarde a confirmação do número.");
    } catch (cause) {
      if (!alive.current) return;
      if (cause instanceof ApiError) refuse(cause);
      else setProblem(controller.signal.aborted ? "Conexão interrompida. Abra o cadastro novamente."
        : "O cadastro da Meta foi fechado ou não pôde ser concluído. Tente novamente.");
    } finally {
      signup.current = undefined;
      busy.current = false;
      if (alive.current) {
        setPending(false);
        if (sent) { setChannel(undefined); setRevision((value) => value + 1); }
      }
    }
  }

  async function disconnect() {
    if (busy.current || !canConnect || !channel || !["ACTIVE", "FAILED"].includes(channel.status)) return;
    busy.current = true;
    setPending(true);
    setProblem("");
    setNotice("");
    const controller = new AbortController();
    signup.current = controller;
    let sent = false;
    try {
      await guard(() => {
        if (controller.signal.aborted || !alive.current) throw new DOMException("Cancelled", "AbortError");
        sent = true;
        return disconnectChannel(target);
      }, controller.signal);
      if (alive.current) setNotice("Desconexão solicitada. Aguarde a confirmação.");
    } catch (cause) { if (alive.current) refuse(clinicApiError(cause)); }
    finally {
      signup.current = undefined;
      busy.current = false;
      if (alive.current) {
        setPending(false);
        setConfirming(false);
        if (sent) { setChannel(undefined); setRevision((value) => value + 1); }
      }
    }
  }

  if (denied) return null;
  const processing = channel?.status === "CONNECTING" || channel?.status === "DISCONNECTING";
  return (
    <Card aria-label={clinicName ? `WhatsApp de ${clinicName}` : "WhatsApp da clínica"}>
      <CardHeader>
        <CardTitle className="flex items-center gap-2"><MessageCircle aria-hidden="true" size={19} />WhatsApp{clinicName ? ` · ${clinicName}` : ""}</CardTitle>
        <p className="text-sm text-muted-foreground">Conecte o número usado no atendimento da clínica.</p>
      </CardHeader>
      <CardContent className="space-y-4">
        {configured && canConnect && <Script id="branly-meta-sdk" src="https://connect.facebook.net/pt_BR/sdk.js"
          strategy="afterInteractive" onReady={initializeSdk} onError={() => setSdkFailed(true)} />}
        {!channel && !problem && <p role="status" className="text-sm text-muted-foreground">Consultando WhatsApp…</p>}
        {channel && <div className="rounded-lg border bg-muted/40 p-4">
          <p className="font-semibold">{statusLabels[channel.status]}</p>
          {channel.displayPhoneNumber && <p className="mt-1 text-sm" translate="no">{channel.displayPhoneNumber}</p>}
          {processing && <p className="mt-1 text-sm text-muted-foreground" role="status">Aguarde enquanto o pedido é processado.</p>}
          {channel.status === "FAILED" && <p className="mt-1 text-sm text-destructive">{channelFailure(channel.failureCode)} Desconecte para tentar novamente.</p>}
        </div>}
        {problem && <p role="alert" className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive">{problem}</p>}
        {notice && <p role="status" className="text-sm">{notice}</p>}
        {canConnect && channel?.status === "NONE" && !configured && <p className="text-sm text-muted-foreground">
          A conexão com a Meta ainda não foi habilitada neste ambiente.
        </p>}
        {canConnect && channel?.status === "NONE" && configured && sdkFailed && <p role="alert" className="text-sm text-destructive">
          Não foi possível carregar o cadastro da Meta. Recarregue a página para tentar novamente.
        </p>}
        <div className="flex flex-wrap gap-2">
          {canConnect && channel?.status === "NONE" && <Button type="button" className="min-h-11"
            disabled={pending || !configured || !sdkReady || sdkFailed} onClick={() => void connect()}>
            {pending ? "Aguardando cadastro…" : "Conectar WhatsApp"}
          </Button>}
          {canConnect && channel && ["ACTIVE", "FAILED"].includes(channel.status) && <Button type="button" variant="outline"
            className="min-h-11" disabled={pending} onClick={() => setConfirming(true)}>Desconectar</Button>}
          <Button type="button" variant="outline" className="min-h-11 gap-2" disabled={pending}
            onClick={() => { setNotice(""); setProblem(""); setRevision((value) => value + 1); }}>
            <RefreshCw size={16} aria-hidden="true" />Atualizar situação
          </Button>
        </div>
        {confirming && <Dialog.Root open onOpenChange={(open) => { if (!open && !pending) setConfirming(false); }}>
          <Dialog.Portal>
            <Dialog.Backdrop className="fixed inset-0 z-40 bg-slate-950/55" />
            <Dialog.Popup className="fixed top-1/2 left-1/2 z-50 w-[calc(100%-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2 space-y-4 rounded-xl border bg-card p-5 shadow-xl">
              <Dialog.Title className="text-xl font-bold">Desconectar WhatsApp?</Dialog.Title>
              <Dialog.Description className="text-sm text-muted-foreground">As mensagens deixam de chegar ao Branly quando a desconexão for concluída.</Dialog.Description>
              <div className="flex flex-wrap justify-end gap-2">
                <Dialog.Close render={<Button type="button" variant="outline" className="min-h-11" disabled={pending} />}>Cancelar</Dialog.Close>
                <Button type="button" variant="destructive" className="min-h-11" disabled={pending} onClick={() => void disconnect()}>
                  {pending ? "Desconectando…" : "Confirmar desconexão"}
                </Button>
              </div>
            </Dialog.Popup>
          </Dialog.Portal>
        </Dialog.Root>}
      </CardContent>
    </Card>
  );
}
