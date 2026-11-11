import { ApiError, apiRequest } from "@/lib/api/client";

export type Dashboard = {
  today: string;
  timeZone: string;
  leadsToday: number;
  salesTodayCount: number;
  salesTodayCents: number;
  salesMonthCount: number;
  salesMonthCents: number;
  tasksOverdue: number;
  tasksToday: number;
};

type Count = { key: string; count: number };
type NamedCount = { id: string; name: string; count: number };
type ProcedureRank = NamedCount & { amountCents: number };

export type MonthlyReport = {
  month: string;
  timeZone: string;
  contactsCreated: number;
  leadsByOrigin: Count[];
  leadsByTemperature: Count[];
  salesClosed: number;
  lost: number;
  conversionPercent: number | null;
  lostByReason: NamedCount[];
  coldReverted: number;
  salesCount: number;
  revenueCents: number;
  topProcedures: ProcedureRank[];
};

const monthPattern = /^\d{4}-(0[1-9]|1[0-2])$/;
const dayPattern = /^\d{4}-\d{2}-\d{2}$/;

function safeTotals(...values: number[]) {
  return values.every((value) => Number.isSafeInteger(value) && value >= 0);
}

function validPeriod(value: string, pattern: RegExp) {
  return typeof value === "string" && pattern.test(value);
}

function invalidReport() {
  return new ApiError(200, "UNEXPECTED_RESPONSE", "A API não retornou indicadores válidos para este período.");
}

export async function getDashboard(signal: AbortSignal) {
  const data = await apiRequest<Dashboard | null>("/v1/clinic/dashboard", { signal });
  if (!data || !validPeriod(data.today, dayPattern) || !Number.isFinite(Date.parse(data.today))
      || typeof data.timeZone !== "string" || !data.timeZone
      || !safeTotals(data.leadsToday, data.salesTodayCount, data.salesTodayCents, data.salesMonthCount,
        data.salesMonthCents, data.tasksOverdue, data.tasksToday)) throw invalidReport();
  return data;
}

export async function getMonthlyReport(month: string | undefined, signal: AbortSignal) {
  const query = month ? `?${new URLSearchParams({ month })}` : "";
  const data = await apiRequest<MonthlyReport | null>(`/v1/clinic/reports/monthly${query}`, { signal });
  if (!data || !validPeriod(data.month, monthPattern) || (month && data.month !== month)
      || typeof data.timeZone !== "string" || !data.timeZone
      || !safeTotals(data.contactsCreated, data.salesClosed, data.lost, data.coldReverted, data.salesCount, data.revenueCents)
      || !(data.conversionPercent === null || (Number.isFinite(data.conversionPercent) && data.conversionPercent >= 0 && data.conversionPercent <= 100))
      || ![data.leadsByOrigin, data.leadsByTemperature].every((rows) => Array.isArray(rows) && rows.every((row) => row && typeof row.key === "string" && safeTotals(row.count)))
      || !Array.isArray(data.lostByReason) || !data.lostByReason.every((row) => row && typeof row.id === "string" && typeof row.name === "string" && safeTotals(row.count))
      || !Array.isArray(data.topProcedures) || !data.topProcedures.every((row) => row && typeof row.id === "string" && typeof row.name === "string" && safeTotals(row.count, row.amountCents))) {
    throw invalidReport();
  }
  return data;
}

export function reportProblem(error: ApiError) {
  switch (error.code) {
    case "CLINIC_ACCESS_DENIED":
    case "ACCESS_DENIED": return "Sua conta não tem permissão para consultar estes indicadores.";
    case "INVALID_INPUT": return "Selecione um mês válido, de janeiro de 2000 até o mês atual da clínica.";
    case "CLINIC_DATA_SCHEMA_OUTDATED": return "Os indicadores estão indisponíveis enquanto o ambiente da clínica é atualizado.";
    case "SERVICE_UNAVAILABLE":
    case "NETWORK_ERROR": return "A conexão com os indicadores está indisponível. Tente novamente em instantes.";
    case "UNEXPECTED_RESPONSE": return "A API não retornou indicadores válidos. Tente novamente em instantes.";
    default: return "Não foi possível consultar os indicadores. Tente novamente em instantes.";
  }
}
