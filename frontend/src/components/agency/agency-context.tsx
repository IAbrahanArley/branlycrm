"use client";

import { createContext, useContext } from "react";
import type { AgencyMembership } from "@/lib/api/agencies";

export const AgencyContext = createContext<{ agencies: AgencyMembership[]; subjectId: string; refresh: () => void } | null>(null);

export function useAgencies() {
  const context = useContext(AgencyContext);
  if (!context) throw new Error("A página requer uma agência confirmada.");
  return context;
}
