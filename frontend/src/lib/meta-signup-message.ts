export function parseSignupMessage(origin: string, value: unknown) {
  if (!["https://www.facebook.com", "https://web.facebook.com", "https://business.facebook.com"].includes(origin)) return null;
  let message = value;
  if (typeof message === "string") {
    try { message = JSON.parse(message); } catch { return null; }
  }
  if (!message || typeof message !== "object") return null;
  const event = message as { type?: unknown; event?: unknown; data?: { phone_number_id?: unknown } };
  if (event.type !== "WA_EMBEDDED_SIGNUP") return null;
  if (event.event === "CANCEL" || event.event === "ERROR") return { kind: "cancel" as const };
  if (event.event !== "FINISH" && event.event !== "FINISH_WHATSAPP_BUSINESS_APP_ONBOARDING") return null;
  const id = event.data?.phone_number_id;
  return {
    kind: "finish" as const,
    phoneNumberId: typeof id === "string" && /^[0-9]{1,32}$/.test(id) ? id : undefined,
    coexistence: event.event === "FINISH_WHATSAPP_BUSINESS_APP_ONBOARDING",
  };
}
