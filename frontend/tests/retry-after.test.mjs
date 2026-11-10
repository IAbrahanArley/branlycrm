import { test } from "node:test";
import assert from "node:assert/strict";
import { loginRetryDeadline } from "../src/lib/api/retry-after.ts";

const now = Date.parse("Sat, 07 Nov 2026 12:00:00 GMT");

test("converts Retry-After seconds to an absolute deadline", () => {
  assert.equal(loginRetryDeadline(" 90 ", now), now + 90_000);
  assert.equal(loginRetryDeadline("0", now), now);
});

test("honors an HTTP date and allows an already elapsed deadline", () => {
  assert.equal(loginRetryDeadline("Sat, 07 Nov 2026 12:01:30 GMT", now), now + 90_000);
  assert.equal(loginRetryDeadline("Sat, 07 Nov 2026 11:59:50 GMT", now), now);
});

test("uses the login window if Retry-After is absent, invalid or overflows", () => {
  for (const value of [null, "", "-1", "invalid date", "99999999999999999999999"]) {
    assert.equal(loginRetryDeadline(value, now), now + 60_000);
  }
});
