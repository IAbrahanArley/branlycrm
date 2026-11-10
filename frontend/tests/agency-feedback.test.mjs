import { test } from "node:test";
import assert from "node:assert/strict";
import { agencyErrorMessage, ownerDeliveryMessage } from "../src/lib/api/agency-feedback.ts";
import { readClinicRequest, writeClinicRequest } from "../src/lib/api/agency-request.ts";

test("explains portfolio policy errors without changing other API messages", () => {
  for (const code of ["AGENCY_NOT_FOUND", "CLINIC_NOT_FOUND", "IDEMPOTENCY_KEY_REUSED", "AGENCY_PENDING_CLINIC_LIMIT", "AGENCY_CLINIC_HAS_OWNER", "INVITATION_SEND_LIMIT"]) {
    assert.notEqual(agencyErrorMessage(code, "fallback"), "fallback");
  }
  assert.equal(agencyErrorMessage("constructor", "fallback"), "fallback");
});

test("distinguishes invitation delivery, delivery failure and idempotent replay", () => {
  assert.match(ownerDeliveryMessage("SENT"), /convite do gestor enviado/i);
  assert.match(ownerDeliveryMessage("FAILED"), /e-mail não foi enviado.*Reemitir/);
  assert.match(ownerDeliveryMessage("NOT_SENT"), /já havia sido processada.*Nenhum novo e-mail/);
});

test("retains only the request identifier, scoped to the account and agency", () => {
  const entries = new Map();
  const storage = { getItem: (key) => entries.get(key) ?? null, setItem: (key, value) => entries.set(key, value), removeItem: (key) => entries.delete(key) };
  const requestId = "00000000-0000-4000-8000-000000000001";
  writeClinicRequest("account-a", "agency-a", requestId, storage);
  assert.deepEqual([...entries.values()], [requestId]);
  assert.equal(readClinicRequest("account-a", "agency-a", storage), requestId);
  assert.equal(readClinicRequest("account-a", "agency-b", storage), undefined);
  assert.equal(readClinicRequest("account-b", "agency-a", storage), undefined);
  writeClinicRequest("account-a", "agency-a", undefined, storage);
  assert.equal(readClinicRequest("account-a", "agency-a", storage), undefined);
});
