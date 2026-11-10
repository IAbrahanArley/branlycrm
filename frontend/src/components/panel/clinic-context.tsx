"use client";

import { createContext, useContext } from "react";
import type { CurrentClinic } from "@/lib/api/auth";

export const ClinicContext = createContext<{ clinic: CurrentClinic; subjectId: string; refresh: () => void } | null>(null);

export function useClinic() {
  const context = useContext(ClinicContext);
  if (!context) throw new Error("A página requer uma clínica confirmada.");
  return context;
}
