import assert from "node:assert/strict";
import { test } from "node:test";
import { parseSignupMessage } from "../src/lib/meta-signup-message.ts";

test("accepts completion info only from explicit Meta HTTPS origins", () => {
  const message = { type: "WA_EMBEDDED_SIGNUP", event: "FINISH", data: { phone_number_id: "12345" } };
  assert.deepEqual(parseSignupMessage("https://www.facebook.com", JSON.stringify(message)), {
    kind: "finish", phoneNumberId: "12345", coexistence: false,
  });
  for (const origin of ["https://evilfacebook.com", "https://www.facebook.com.evil.test", "http://www.facebook.com", "null"]) {
    assert.equal(parseSignupMessage(origin, message), null);
  }
});

test("identifies coexistence and discards invalid phone hints and unrelated events", () => {
  assert.deepEqual(parseSignupMessage("https://web.facebook.com", {
    type: "WA_EMBEDDED_SIGNUP", event: "FINISH_WHATSAPP_BUSINESS_APP_ONBOARDING", data: { phone_number_id: "../bad" },
  }), { kind: "finish", phoneNumberId: undefined, coexistence: true });
  assert.equal(parseSignupMessage("https://www.facebook.com", "{"), null);
  assert.equal(parseSignupMessage("https://www.facebook.com", { type: "OTHER", event: "FINISH" }), null);
});
