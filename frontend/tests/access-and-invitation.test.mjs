import { test } from "node:test";
import assert from "node:assert/strict";
import { accessKind } from "../src/lib/api/access-kind.ts";
import { invitationTokenFromFragment, validInvitationPassword } from "../src/lib/api/invitation-rules.ts";

test("distinguishes clinic, agency, both and no access", () => {
  assert.equal(accessKind(1, 0), "clinic");
  assert.equal(accessKind(0, 1), "agency");
  assert.equal(accessKind(2, 3), "both");
  assert.equal(accessKind(0, 0), "none");
});

test("accepts only a single token in the invitation fragment", () => {
  const token = "A".repeat(43);
  assert.equal(invitationTokenFromFragment(`#token=${token}`), token);
  assert.equal(invitationTokenFromFragment(`#token=${token}&token=${token}`), null);
  assert.equal(invitationTokenFromFragment("#token=invalid"), null);
  assert.equal(invitationTokenFromFragment(""), null);
});

test("uses Unicode code points and UTF-8 bytes for invitation passwords", () => {
  assert.equal(validInvitationPassword("😀".repeat(11)), false);
  assert.equal(validInvitationPassword("😀".repeat(12)), true);
  assert.equal(validInvitationPassword("😀".repeat(18)), true);
  assert.equal(validInvitationPassword("😀".repeat(18) + "a"), false);
});
