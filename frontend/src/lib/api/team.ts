import { apiRequest } from "@/lib/api/client";

export const teamRoles = ["OWNER", "MANAGER", "ATTENDANT", "VIEWER"] as const;
export type TeamRole = (typeof teamRoles)[number];

export type Member = { subjectId: string; displayName: string; email: string; role: TeamRole; active: boolean };
export type TeamInvitation = { invitationId: string; email: string; role: TeamRole; expiresAt: string; expired: boolean };
export type IssuedInvitation = Omit<TeamInvitation, "expired"> & { delivery: "SENT" | "FAILED" };
export type TeamPage<T> = { items: T[]; page: number; pageSize: number; total: number };
export type MemberFilters = { role: TeamRole | ""; active: "true" | "false" | ""; page: number };

const base = "/v1/clinic/team";
const invitations = `${base}/invitations` as const;
const headers = { "Content-Type": "application/json" };
export const teamPageSize = 20;

export function listMembers(filters: MemberFilters, signal: AbortSignal) {
  const query = new URLSearchParams({ page: String(filters.page), pageSize: String(teamPageSize) });
  if (filters.role) query.set("role", filters.role);
  if (filters.active) query.set("active", filters.active);
  return apiRequest<TeamPage<Member>>(`${base}?${query}`, { signal });
}

export function listTeamInvitations(page: number, signal: AbortSignal) {
  return apiRequest<TeamPage<TeamInvitation>>(`${invitations}?page=${page}&pageSize=${teamPageSize}`, { signal });
}

export function changeMemberRole(subjectId: string, role: TeamRole) {
  return apiRequest<Member>(`${base}/${subjectId}/role`, { method: "PUT", headers, body: JSON.stringify({ role }) });
}

export function deactivateMember(subjectId: string) {
  return apiRequest(`${base}/${subjectId}`, { method: "DELETE", response: "empty" });
}

export function inviteMember(email: string, role: TeamRole) {
  return apiRequest<IssuedInvitation>(invitations, { method: "POST", headers, body: JSON.stringify({ email, role }) });
}

export function resendTeamInvitation(invitationId: string) {
  return apiRequest<IssuedInvitation>(`${invitations}/${invitationId}/resend`, { method: "POST" });
}

export function revokeTeamInvitation(invitationId: string) {
  return apiRequest(`${invitations}/${invitationId}`, { method: "DELETE", response: "empty" });
}
