import { ApiError, apiRequest } from "@/lib/api/client";
import { accessKind } from "@/lib/api/access-kind";

type Identity = {
  subjectId: string;
  email: string;
};

export type ClinicMembership = { tenantId: string; clinicName: string; role: string };
type AgencyMembership = { agencyId: string; name: string };
export type CurrentClinic = { tenantId: string; role: string; permissions: Record<string, string> };

export async function getPanelAccess(signal: AbortSignal) {
  const [clinics, agencies] = await Promise.all([
    apiRequest<ClinicMembership[]>("/v1/auth/clinics", { signal }),
    apiRequest<AgencyMembership[]>("/v1/agencies", { signal }),
  ]);
  if (!Array.isArray(clinics) || !Array.isArray(agencies) ||
      !clinics.every((clinic) => typeof clinic?.tenantId === "string" &&
        typeof clinic.clinicName === "string" && typeof clinic.role === "string") ||
      !agencies.every((agency) => typeof agency?.agencyId === "string" && typeof agency.name === "string")) {
    throw new ApiError(200, "UNEXPECTED_RESPONSE", "Não foi possível confirmar as áreas disponíveis.");
  }
  return { kind: accessKind(clinics.length, agencies.length), clinics, agencies };
}

export async function getCurrentClinic(signal?: AbortSignal): Promise<CurrentClinic | null> {
  try {
    const clinic = await apiRequest<CurrentClinic | null>("/v1/auth/clinic", { signal });
    if (typeof clinic?.tenantId !== "string" || typeof clinic.role !== "string" ||
        !clinic.permissions || typeof clinic.permissions !== "object" || Array.isArray(clinic.permissions)) {
      throw new ApiError(200, "UNEXPECTED_RESPONSE", "Não foi possível confirmar a clínica atual.");
    }
    return clinic;
  } catch (error) {
    if (error instanceof ApiError && error.status === 409 && error.code === "CLINIC_NOT_SELECTED") return null;
    throw error;
  }
}

export async function selectClinic(tenantId: string) {
  const selection = await apiRequest<{ tenantId: string } | null>(`/v1/auth/clinic/${tenantId}`, { method: "PUT" });
  if (selection?.tenantId !== tenantId) {
    throw new ApiError(200, "UNEXPECTED_RESPONSE", "Não foi possível confirmar a seleção da clínica.");
  }
}

export async function getIdentity(signal: AbortSignal) {
  const identity = await apiRequest<Identity | null>("/v1/auth/me", { signal });
  if (typeof identity?.subjectId !== "string" || !identity.subjectId ||
      typeof identity.email !== "string" || !identity.email) {
    throw new ApiError(200, "UNEXPECTED_RESPONSE", "Não foi possível confirmar a identidade da sessão.");
  }
  return identity;
}

export function login(email: string, password: string) {
  return apiRequest("/v1/auth/login", {
    method: "POST",
    body: new URLSearchParams({ email, password }),
    response: "empty",
  });
}

export function logout() {
  return apiRequest("/v1/auth/logout", { method: "POST", response: "empty" });
}
