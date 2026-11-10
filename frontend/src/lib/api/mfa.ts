import { ApiError, apiRequest } from "@/lib/api/client";

export type MfaStatus = {
  state: "COMPLETE" | "MFA_PENDING" | "ENROLLMENT_REQUIRED";
  enrolled: boolean;
  required: boolean;
  recoveryCodesRemaining: number;
};

const json = { "Content-Type": "application/json" };
const unexpected = () => new ApiError(200, "UNEXPECTED_RESPONSE", "Não foi possível confirmar a resposta de segurança.");

export async function getMfaStatus(signal?: AbortSignal) {
  const status = await apiRequest<MfaStatus | null>("/v1/auth/mfa", { signal });
  if ((status?.state !== "COMPLETE" && status?.state !== "MFA_PENDING" && status?.state !== "ENROLLMENT_REQUIRED") ||
      typeof status.enrolled !== "boolean" || typeof status.required !== "boolean" ||
      !Number.isSafeInteger(status.recoveryCodesRemaining) || status.recoveryCodesRemaining < 0) throw unexpected();
  return status;
}

export async function setupMfa(code?: string) {
  const setup = await apiRequest<{ secret: string } | null>("/v1/auth/mfa/totp/setup", {
    method: "POST", headers: json, body: JSON.stringify(code ? { code } : {}),
  });
  if (typeof setup?.secret !== "string" || !setup.secret) throw unexpected();
  return setup.secret;
}

export function verifyMfa(proof: { code: string } | { recoveryCode: string }) {
  return apiRequest("/v1/auth/mfa/verify", {
    method: "POST", headers: json, body: JSON.stringify(proof), response: "empty",
  });
}

export async function issueRecoveryCodes(action: "confirm" | "regenerate", code: string) {
  const path = action === "confirm" ? "/v1/auth/mfa/totp/confirm" : "/v1/auth/mfa/recovery-codes";
  const result = await apiRequest<{ recoveryCodes: string[] } | null>(path, {
    method: "POST", headers: json, body: JSON.stringify({ code }),
  });
  if (!Array.isArray(result?.recoveryCodes) || result.recoveryCodes.length !== 10 ||
      !result.recoveryCodes.every((value) => typeof value === "string" && value.length > 0)) throw unexpected();
  return result.recoveryCodes;
}

export function disableMfa(code: string) {
  return apiRequest("/v1/auth/mfa/totp", {
    method: "DELETE", headers: json, body: JSON.stringify({ code }), response: "empty",
  });
}
