import assert from "node:assert/strict";
import { test } from "node:test";
import { readFile } from "node:fs/promises";
import ts from "typescript";

const parserUrl = new URL("../src/lib/meta-signup-message.ts", import.meta.url).href;
const source = (await readFile(new URL("../src/lib/meta-signup.ts", import.meta.url), "utf8"))
  .replace('"./meta-signup-message"', JSON.stringify(parserUrl));
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext } }).outputText;
const { beginMetaSignup } = await import("data:text/javascript;base64," + Buffer.from(compiled).toString("base64"));
globalThis.window = undefined;

test("correlates Meta info with the current popup and cleans up on success", async (t) => {
  const listeners = new Set();
  let callback;
  let closed = 0;
  const popup = { close() { closed++; } };
  const open = function () { assert.equal(this, window); return popup; };
  t.mock.property(globalThis, "window", {
    open,
    addEventListener: (_, handler) => listeners.add(handler),
    removeEventListener: (_, handler) => listeners.delete(handler),
    FB: { login(cb) { callback = cb; window.open("https://www.facebook.com"); } },
  });
  const controller = new AbortController();
  const result = beginMetaSignup("123", controller.signal);
  assert.equal(window.open, open);
  await assert.rejects(beginMetaSignup("123", new AbortController().signal));
  const send = (source, phone) => [...listeners].forEach((handler) => handler({ source,
    origin: "https://www.facebook.com", data: { type: "WA_EMBEDDED_SIGNUP", event: "FINISH", data: { phone_number_id: phone } },
  }));
  send({}, "999"); // A previous popup cannot provide the new attempt's metadata.
  callback({ authResponse: { code: "synthetic-code" } });
  send(popup, "123");
  assert.deepEqual(await result, { code: "synthetic-code", phoneNumberId: "123", coexistence: false });
  assert.equal(listeners.size, 0);
  assert.equal(closed, 1);
});

test("aborting closes only this popup, ignores late callback and allows another attempt", async (t) => {
  let callback;
  let closed = 0;
  const popup = { close() { closed++; } };
  const listeners = new Set();
  const open = () => popup;
  t.mock.property(globalThis, "window", { open,
    addEventListener: (_, handler) => listeners.add(handler),
    removeEventListener: (_, handler) => listeners.delete(handler),
    FB: { login(cb) { callback = cb; window.open(); } },
  });
  const controller = new AbortController();
  const result = beginMetaSignup("123", controller.signal);
  controller.abort();
  await assert.rejects(result, /SIGNUP_CANCELLED/);
  callback({ authResponse: { code: "expired-code" } });
  assert.equal(closed, 1);
  assert.equal(listeners.size, 0);
  window.FB.login = () => {}; // An SDK without a synchronous popup must fail closed.
  await assert.rejects(beginMetaSignup("123", new AbortController().signal), /SIGNUP_POPUP_UNAVAILABLE/);
  assert.equal(window.open, open);
});
