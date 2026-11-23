import { ApiError, apiRequest } from "@/lib/api/client";

export type ChannelTarget = { tenantId: string; agencyId?: string };
export type ChannelStatus = "NONE" | "CONNECTING" | "ACTIVE" | "FAILED" | "DISCONNECTING";
export type Channel = {
  status: ChannelStatus;
  displayPhoneNumber: string | null;
  failureCode: string | null;
  connectedAt: string | null;
  updatedAt: string | null;
};
export type SignupCode = { code: string; phoneNumberId?: string; coexistence?: boolean };

const statuses = ["NONE", "CONNECTING", "ACTIVE", "FAILED", "DISCONNECTING"];
const uuid = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i;

function route({ agencyId, tenantId }: ChannelTarget): `/v1/${string}` {
  if (!uuid.test(tenantId) || (agencyId && !uuid.test(agencyId))) throw new TypeError("Clínica inválida.");
  return agencyId ? `/v1/agencies/${agencyId}/clinics/${tenantId}/whatsapp/channel` : "/v1/clinic/whatsapp/channel";
}

async function readChannel(path: `/v1/${string}`, options?: { method?: "POST"; body?: string; signal?: AbortSignal }) {
  const result = await apiRequest<Channel | null>(path, {
    ...options, headers: options?.body ? { "Content-Type": "application/json" } : undefined,
  });
  if (!result || !statuses.includes(result.status)
      || ![result.displayPhoneNumber, result.failureCode, result.connectedAt, result.updatedAt].every((value) => value === null || typeof value === "string")) {
    throw new ApiError(200, "UNEXPECTED_RESPONSE", "Não foi possível confirmar a situação do WhatsApp.");
  }
  return result;
}

export function getChannel(target: ChannelTarget, signal?: AbortSignal) {
  return readChannel(route(target), { signal });
}

export async function connectChannel(target: ChannelTarget, signup: SignupCode) {
  const result = await readChannel(`${route(target)}/connect`, { method: "POST", body: JSON.stringify(signup) });
  if (!["CONNECTING", "ACTIVE", "FAILED"].includes(result.status)) {
    throw new ApiError(202, "UNEXPECTED_RESPONSE", "A conexão não foi confirmada.");
  }
  return result;
}

export async function disconnectChannel(target: ChannelTarget) {
  const result = await readChannel(`${route(target)}/disconnect`, { method: "POST" });
  if (!["DISCONNECTING", "NONE"].includes(result.status)) {
    throw new ApiError(202, "UNEXPECTED_RESPONSE", "A desconexão não foi confirmada.");
  }
  return result;
}

const errors = new Map([
  ["CHANNEL_ALREADY_CONNECTED", "Esta clínica já tem um canal. Atualize a situação antes de continuar."],
  ["CHANNEL_BUSY", "O WhatsApp está processando outra alteração. Aguarde e atualize a situação."],
  ["CHANNEL_NOT_CONNECTED", "O canal já está desconectado. Atualize a situação."],
  ["CHANNEL_CODE_INVALID", "A autorização da Meta expirou ou foi recusada. Abra a conexão novamente."],
  ["CHANNEL_META_UNAVAILABLE", "A Meta está indisponível. Tente conectar novamente em instantes."],
  ["CHANNEL_NOT_CONFIGURED", "A conexão do WhatsApp ainda precisa ser configurada neste ambiente."],
  ["INVALID_INPUT", "A Meta não retornou os dados esperados. Abra a conexão novamente."],
  ["CSRF_INVALID", "A confirmação de segurança expirou. Atualize a situação e tente novamente."],
]);

export function channelError(error: ApiError) {
  return errors.get(error.code) ?? "Não foi possível confirmar a operação. Atualize a situação antes de continuar.";
}

const failures = new Map([
  ["TOKEN_INVALID", "A autorização da Meta perdeu a validade."],
  ["NUMBER_IN_USE", "Esse número já está conectado a outra clínica."],
  ["AMBIGUOUS_NUMBER", "Há mais de um número disponível. Escolha o número na próxima conexão."],
  ["NUMBER_NOT_GRANTED", "A autorização não permite acessar o número escolhido."],
  ["NO_NUMBER", "Nenhum número foi disponibilizado pela Meta."],
  ["META_UNAVAILABLE", "A Meta está indisponível no momento."],
]);

export function channelFailure(code: string | null) {
  return failures.get(code ?? "") ?? "Houve uma falha na conexão.";
}
