import { test } from "node:test";
import assert from "node:assert/strict";

const csrf = (token) => Response.json({
  token,
  headerName: "X-Test-Csrf",
  parameterName: "_csrf",
});
const empty = () => new Response(null, { status: 204 });
const failure = (status, code) => Response.json(
  { code, message: "Mensagem da API.", fields: ["email"] },
  { status, headers: { "Retry-After": "10" } },
);

test("only retains the existing contact ID on a duplicate phone response", async (t) => {
  const { apiRequest } = await import("../src/lib/api/client.ts?duplicate-contact");
  const contactId = "00000000-0000-4000-8000-000000000001";
  const responses = [
    csrf("contacts"),
    Response.json({ code: "CONTACT_DUPLICATE_PHONE", contactId }, { status: 409 }),
    Response.json({ code: "CONTACT_DUPLICATE_PHONE" }, { status: 409 }),
    Response.json({ code: "CONTACT_STALE", contactId }, { status: 409 }),
  ];
  t.mock.method(globalThis, "fetch", async () => responses.shift());
  const create = () => apiRequest("/v1/clinic/contacts", { method: "POST" });

  await assert.rejects(create(), { code: "CONTACT_DUPLICATE_PHONE", contactId });
  await assert.rejects(create(), { code: "CONTACT_DUPLICATE_PHONE", contactId: null });
  await assert.rejects(create(), { code: "CONTACT_STALE", contactId: null });
});

test("uses the supplied CSRF header and rotates the token after login and logout", async (t) => {
  const { apiRequest } = await import("../src/lib/api/client.ts?rotation");
  const responses = [csrf("before"), empty(), csrf("after-login"), empty(), csrf("after-logout"), empty()];
  const fetch = t.mock.method(globalThis, "fetch", async () => responses.shift());
  const body = new URLSearchParams({ email: "teste@example.test", password: "sintetica" });

  await apiRequest("/v1/auth/login", { method: "POST", body, response: "empty" });
  await apiRequest("/v1/auth/logout", { method: "POST", response: "empty" });
  await apiRequest("/v1/clinic/team/membership", { method: "DELETE", response: "empty" });

  const calls = fetch.mock.calls.map(({ arguments: args }) => args);
  assert.deepEqual(calls.map(([path]) => path), [
    "/api/v1/auth/csrf", "/api/v1/auth/login",
    "/api/v1/auth/csrf", "/api/v1/auth/logout",
    "/api/v1/auth/csrf", "/api/v1/clinic/team/membership",
  ]);
  assert.deepEqual([1, 3, 5].map((i) => calls[i][1].headers.get("X-Test-Csrf")), [
    "before", "after-login", "after-logout",
  ]);
  assert.equal(calls[1][1].body, body);
  for (const [, options] of calls) {
    assert.equal(options.credentials, "same-origin");
    assert.equal(options.cache, "no-store");
    assert.equal(options.redirect, "error");
  }
});

test("distinguishes invalid credentials and revocation and preserves the API error details", async (t) => {
  const { apiRequest, onSessionEnd } = await import("../src/lib/api/client.ts?revocation");
  const reasons = [];
  onSessionEnd((reason) => reasons.push(reason));
  const responses = [
    csrf("previous"), failure(401, "INVALID_CREDENTIALS"),
    failure(401, "SESSION_REVOKED"), csrf("new"), failure(429, "RATE_LIMITED"),
  ];
  const fetch = t.mock.method(globalThis, "fetch", async () => responses.shift());

  await assert.rejects(apiRequest("/v1/auth/login", { method: "POST" }), { code: "INVALID_CREDENTIALS" });
  assert.deepEqual(reasons, []);
  await assert.rejects(apiRequest("/v1/auth/me"), { code: "SESSION_REVOKED" });
  assert.deepEqual(reasons, ["SESSION_REVOKED"]);
  await assert.rejects(apiRequest("/v1/auth/login", { method: "POST" }), {
    status: 429,
    code: "RATE_LIMITED",
    message: "Mensagem da API.",
    fields: ["email"],
    retryAfter: "10",
  });
  assert.equal(fetch.mock.calls[4].arguments[1].headers.get("X-Test-Csrf"), "new");
});

test("does not replay a refused mutation and reacquires CSRF on the next attempt", async (t) => {
  const { apiRequest } = await import("../src/lib/api/client.ts?csrf-invalid");
  const responses = [csrf("expired"), failure(403, "CSRF_INVALID"), csrf("new"), empty()];
  const fetch = t.mock.method(globalThis, "fetch", async () => responses.shift());
  const options = { method: "DELETE", response: "empty" };

  await assert.rejects(apiRequest("/v1/clinic/team/membership", options), { code: "CSRF_INVALID" });
  assert.equal(fetch.mock.callCount(), 2);
  await apiRequest("/v1/clinic/team/membership", options);
  assert.equal(fetch.mock.calls[3].arguments[1].headers.get("X-Test-Csrf"), "new");
});

test("keeps a successful login when its CSRF refresh fails", async (t) => {
  const { apiRequest } = await import("../src/lib/api/client.ts?refresh-failure");
  const responses = [csrf("before"), empty(), failure(503, "SERVICE_UNAVAILABLE"), csrf("new"), empty(), csrf("anonymous")];
  const fetch = t.mock.method(globalThis, "fetch", async () => responses.shift());
  const options = { method: "POST", response: "empty" };

  await apiRequest("/v1/auth/login", options);
  await apiRequest("/v1/auth/logout", options);
  assert.equal(fetch.mock.calls[4].arguments[1].headers.get("X-Test-Csrf"), "new");
});

test("does not carry a pending mutation or a late 401 into a newly logged-in session", async (t) => {
  const { apiRequest, onSessionEnd } = await import("../src/lib/api/client.ts?race");
  const loginResponse = Promise.withResolvers();
  const staleCsrf = Promise.withResolvers();
  const oldIdentity = Promise.withResolvers();
  const responses = [
    csrf("before"), loginResponse.promise, failure(403, "CSRF_INVALID"),
    staleCsrf.promise, oldIdentity.promise, csrf("new-session"),
  ];
  const fetch = t.mock.method(globalThis, "fetch", async () => responses.shift());
  const reasons = [];
  onSessionEnd((reason) => reasons.push(reason));
  const login = apiRequest("/v1/auth/login", { method: "POST", response: "empty" });
  await new Promise((resolve) => setImmediate(resolve));
  await assert.rejects(apiRequest("/v1/clinic/team/membership", { method: "DELETE" }), { code: "CSRF_INVALID" });

  const pendingMutation = apiRequest("/v1/clinic/team/membership", { method: "DELETE" });
  const pendingIdentity = apiRequest("/v1/auth/me");
  loginResponse.resolve(empty());
  await login;
  staleCsrf.resolve(csrf("stale"));
  oldIdentity.resolve(failure(401, "SESSION_REVOKED"));

  await assert.rejects(pendingMutation, { code: "SESSION_CHANGED" });
  await assert.rejects(pendingIdentity, { code: "SESSION_REVOKED" });
  assert.deepEqual(reasons, []);
  assert.equal(fetch.mock.callCount(), 6);
});

test("rejects unsafe routes and reports unexpected proxy responses without exposing their bodies", async (t) => {
  const { apiRequest } = await import("../src/lib/api/client.ts?proxy");
  const fetch = t.mock.method(globalThis, "fetch", async () => new Response("<html>Proxy</html>", { status: 502 }));

  await assert.rejects(apiRequest("/v1/../../outside", { method: "POST" }), TypeError);
  assert.equal(fetch.mock.callCount(), 0);
  await assert.rejects(apiRequest("/v1/auth/me"), {
    status: 502,
    code: "UNEXPECTED_RESPONSE",
    message: "Não foi possível concluir a solicitação. Tente novamente.",
  });
});

test("explains state conflicts and does not replay the operation", async (t) => {
  const { apiRequest } = await import("../src/lib/api/client.ts?state-conflict");
  const responses = [csrf("current"), failure(409, "STATE_CONFLICT")];
  const fetch = t.mock.method(globalThis, "fetch", async () => responses.shift());

  await assert.rejects(apiRequest("/v1/invitations/accept", { method: "POST" }), {
    status: 409,
    code: "STATE_CONFLICT",
    message: "A operação conflita com o estado atual. Recarregue os dados e tente novamente.",
  });
  assert.equal(fetch.mock.callCount(), 2);
});

test("reports MFA gates without expiring the session", async (t) => {
  const { apiRequest, onSessionEnd, onMfaGate } = await import("../src/lib/api/client.ts?mfa-gate");
  const ended = [];
  const gates = [];
  onSessionEnd((reason) => ended.push(reason));
  onMfaGate((reason) => gates.push(reason));
  const responses = [failure(401, "MFA_REQUIRED"), failure(403, "MFA_ENROLLMENT_REQUIRED")];
  t.mock.method(globalThis, "fetch", async () => responses.shift());

  await assert.rejects(apiRequest("/v1/auth/clinics"), { code: "MFA_REQUIRED" });
  await assert.rejects(apiRequest("/v1/agencies"), { code: "MFA_ENROLLMENT_REQUIRED" });
  assert.deepEqual(gates, ["MFA_REQUIRED", "MFA_ENROLLMENT_REQUIRED"]);
  assert.deepEqual(ended, []);
});

test("refreshes CSRF after verification and preserves recovery codes after factor replacement", async (t) => {
  const { apiRequest } = await import("../src/lib/api/client.ts?mfa-rotation");
  const recovery = { recoveryCodes: ["synthetic"] };
  const responses = [csrf("pending"), empty(), csrf("complete"), Response.json(recovery)];
  const fetch = t.mock.method(globalThis, "fetch", async () => responses.shift());

  await apiRequest("/v1/auth/mfa/verify", { method: "POST", response: "empty" });
  assert.deepEqual(await apiRequest("/v1/auth/mfa/totp/confirm", { method: "POST" }), recovery);
  assert.deepEqual(fetch.mock.calls.map(({ arguments: [path] }) => path), [
    "/api/v1/auth/csrf", "/api/v1/auth/mfa/verify", "/api/v1/auth/csrf", "/api/v1/auth/mfa/totp/confirm",
  ]);
  assert.equal(fetch.mock.calls[3].arguments[1].headers.get("X-Test-Csrf"), "complete");
});
