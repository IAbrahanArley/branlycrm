import { apiRequest } from "@/lib/api/client";

export type AgencyMembership = { agencyId: string; name: string };
export type PortfolioClinic = {
  tenantId: string;
  clinicName: string;
  status: "PROVISIONING" | "AWAITING_OWNER" | "ACTIVE" | "SUSPENDED" | "FAILED";
  ownerInvitation: "PENDING" | "EXPIRED" | "ACCEPTED" | "NONE";
  createdAt: string;
};
export type PortfolioPage = { items: PortfolioClinic[]; page: number; pageSize: number; total: number };
export type ClinicDraft = { clinicName: string; ownerEmail: string; requestId: string };
type AgencyClinicResult = Pick<PortfolioClinic, "tenantId" | "clinicName" | "status"> & {
  ownerEmail: string | null;
  ownerInvitationExpiresAt: string | null;
  delivery: "SENT" | "FAILED" | "NOT_SENT";
  created: boolean;
};
type OwnerInvitationResult = {
  invitationId: string; email: string; role: string; expiresAt: string; delivery: "SENT" | "FAILED";
};

const headers = { "Content-Type": "application/json" };

export function listAgencies(signal: AbortSignal) {
  return apiRequest<AgencyMembership[]>("/v1/agencies", { signal });
}

export function listPortfolio(agencyId: string, page: number, signal: AbortSignal) {
  return apiRequest<PortfolioPage>(`/v1/agencies/${agencyId}/clinics?page=${page}&pageSize=20`, { signal });
}

export function createPortfolioClinic(agencyId: string, draft: ClinicDraft) {
  return apiRequest<AgencyClinicResult>(`/v1/agencies/${agencyId}/clinics`, {
    method: "POST", headers, body: JSON.stringify(draft),
  });
}

export function reissueOwnerInvitation(agencyId: string, tenantId: string, ownerEmail: string) {
  return apiRequest<OwnerInvitationResult>(`/v1/agencies/${agencyId}/clinics/${tenantId}/owner-invitation`, {
    method: "POST", headers, body: JSON.stringify({ ownerEmail }),
  });
}
