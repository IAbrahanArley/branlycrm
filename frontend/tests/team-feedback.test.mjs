import { test } from "node:test";
import assert from "node:assert/strict";
import { invitationDeliveryMessage, teamErrorMessage } from "../src/lib/api/team-feedback.ts";

test("explains team policy rejections and keeps unknown error messages", () => {
  for (const code of ["MEMBER_CHANGE_FORBIDDEN", "LAST_OWNER", "INVITATION_FORBIDDEN", "MEMBER_ALREADY_ACTIVE", "INVITATION_SEND_LIMIT"]) {
    assert.notEqual(teamErrorMessage(code, "fallback"), "fallback");
  }
  assert.equal(teamErrorMessage("constructor", "fallback"), "fallback");
});

test("failed e-mail delivery is distinct from failure to create the invitation", () => {
  assert.match(invitationDeliveryMessage("SENT"), /e-mail enviado/);
  assert.match(invitationDeliveryMessage("FAILED"), /ficou pendente.*Reenviar/);
});
