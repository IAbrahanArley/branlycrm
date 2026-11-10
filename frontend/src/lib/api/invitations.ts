import { ApiError, apiRequest } from "@/lib/api/client";

export type InvitationPreview = {
  kind: "CLINIC" | "AGENCY";
  targetName: string;
  role: string;
  requiresLogin: boolean;
  expiresAt: string;
};

const json = { "Content-Type": "application/json" };

export async function previewInvitation(token: string, signal: AbortSignal) {
  const preview = await apiRequest<InvitationPreview | null>("/v1/invitations/preview", {
    method: "POST",
    body: JSON.stringify({ token }),
    headers: json,
    signal,
  });
  if ((preview?.kind !== "CLINIC" && preview?.kind !== "AGENCY") ||
      typeof preview.targetName !== "string" || typeof preview.role !== "string" ||
      typeof preview.requiresLogin !== "boolean") {
    throw new ApiError(200, "UNEXPECTED_RESPONSE", "Não foi possível confirmar os dados do convite.");
  }
  return preview;
}

export function acceptInvitation(token: string, account?: { displayName: string; password: string }) {
  return apiRequest("/v1/invitations/accept", {
    method: "POST",
    headers: json,
    body: JSON.stringify({ token, ...account }),
    response: "empty",
  });
}
