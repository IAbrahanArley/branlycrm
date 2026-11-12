export const originTypes = ["PAID_TRAFFIC", "INSTAGRAM_ORGANIC", "REFERRAL", "GOOGLE", "OTHER", "UNIDENTIFIED"] as const;
export type OriginType = (typeof originTypes)[number];
export type OriginSource = "AD" | "CODE" | "MANUAL" | "NONE";
export type OriginDraft = { type: OriginType; code: string | null; title: string | null };
export type LeadOrigin = OriginDraft & { source: OriginSource };

const labels: Record<OriginType, string> = {
  PAID_TRAFFIC: "Tráfego pago",
  INSTAGRAM_ORGANIC: "Instagram orgânico",
  REFERRAL: "Indicação",
  GOOGLE: "Google",
  OTHER: "Outra origem",
  UNIDENTIFIED: "Não identificada",
};

export const originSources: Record<OriginSource, string> = {
  AD: "Anúncio", CODE: "Código na mensagem", MANUAL: "Registro manual", NONE: "Sem fonte identificada",
};

export function isOriginType(value: string): value is OriginType {
  return Object.hasOwn(labels, value);
}

export function originTypeLabel(value: string) {
  return isOriginType(value) ? labels[value] : "Origem indisponível";
}

export function parseOriginCode(value: string) {
  const code = value.trim();
  return !code ? null : /^[A-Za-z0-9_-]{1,32}$/.test(code) ? code : undefined;
}
