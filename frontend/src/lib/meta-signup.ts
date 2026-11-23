import { parseSignupMessage } from "./meta-signup-message";
import type { SignupCode } from "@/lib/api/whatsapp";

export type MetaSdk = {
  init: (config: { appId: string; version: string; xfbml: boolean; cookie: boolean; autoLogAppEvents: boolean }) => void;
  login: (callback: (response: { authResponse?: { code?: string } }) => void, options: object) => void;
};

declare global {
  interface Window { FB?: MetaSdk }
}

let running = false;

// Only one signup window can own session-info messages in this page at a time.
export function beginMetaSignup(configId: string, signal: AbortSignal): Promise<SignupCode> {
  if (running || !window.FB || signal.aborted) return Promise.reject(new Error("SIGNUP_UNAVAILABLE"));
  running = true;
  return new Promise((resolve, reject) => {
    let settled = false;
    let popup: Window | null = null;
    let code: string | undefined;
    let info: { phoneNumberId?: string; coexistence: boolean } | undefined;
    let settleTimer: ReturnType<typeof setTimeout> | undefined;
    const timeout = setTimeout(() => fail("SIGNUP_TIMEOUT"), 5 * 60_000);
    function cleanup() {
      settled = true;
      running = false;
      clearTimeout(timeout);
      clearTimeout(settleTimer);
      window.removeEventListener("message", message);
      signal.removeEventListener("abort", abort);
      try { popup?.close(); } catch { /* A closed cross-origin popup needs no cleanup. */ }
    }
    function fail(reason: string) {
      if (settled) return;
      code = undefined;
      cleanup();
      reject(new Error(reason));
    }
    function finish() {
      if (settled || !code) return;
      const result = { code, ...info };
      code = undefined;
      cleanup();
      resolve(result);
    }
    function abort() { fail("SIGNUP_CANCELLED"); }
    function message(event: MessageEvent) {
      if (!popup || event.source !== popup) return;
      const parsed = parseSignupMessage(event.origin, event.data);
      if (parsed?.kind === "cancel") { fail("SIGNUP_CANCELLED"); return; }
      if (parsed?.kind !== "finish") return;
      info = { phoneNumberId: parsed.phoneNumberId, coexistence: parsed.coexistence };
      if (code) finish();
    }
    window.addEventListener("message", message);
    signal.addEventListener("abort", abort, { once: true });
    const originalOpen = window.open;
    let opens = 0;
    window.open = function (...args: Parameters<typeof window.open>) {
      const opened = originalOpen.apply(this, args);
      opens += 1;
      if (opens === 1) popup = opened;
      else { try { opened?.close(); } catch { /* Fail closed below. */ } }
      return opened;
    };
    try {
      window.FB!.login((response) => {
        if (signal.aborted || settled) return;
        const received = response.authResponse?.code;
        if (typeof received !== "string" || !received) { fail("SIGNUP_CANCELLED"); return; }
        code = received;
        if (info) finish();
        // Session info and the login callback may arrive in either order.
        else settleTimer = setTimeout(() => fail("SIGNUP_INFO_MISSING"), 1500);
      }, {
        config_id: configId, response_type: "code", override_default_response_type: true,
        extras: { version: "v4", setup: {}, featureType: "whatsapp_business_app_onboarding" },
      });
      if (opens !== 1 || !popup) fail("SIGNUP_POPUP_UNAVAILABLE");
    } catch { fail("SIGNUP_UNAVAILABLE"); }
    finally { window.open = originalOpen; }
  });
}
