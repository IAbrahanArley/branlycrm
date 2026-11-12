import assert from "node:assert/strict";
import { test } from "node:test";
import { taskDeadlineInstant, taskLocalDeadline } from "../src/lib/task-deadline.ts";

test("translates deadlines using the clinic zone and round trips across midnight", () => {
  assert.equal(taskDeadlineInstant("2026-11-12T23:55", "America/Sao_Paulo"), "2026-11-13T02:55:00.000Z");
  assert.equal(taskLocalDeadline("2026-11-13T02:55:00.000Z", "America/Sao_Paulo"), "2026-11-12T23:55");
  assert.equal(taskDeadlineInstant("2026-11-12T09:00", "Asia/Kathmandu"), "2026-11-12T03:15:00.000Z");
});

test("rejects nonexistent or ambiguous local hours and dates outside the API window", () => {
  assert.equal(taskDeadlineInstant("2026-03-08T02:30", "America/New_York"), null);
  assert.equal(taskDeadlineInstant("2026-11-01T01:30", "America/New_York"), null);
  assert.equal(taskDeadlineInstant("2026-02-30T09:00", "UTC"), null);
  assert.equal(taskDeadlineInstant("2100-01-01T00:00", "UTC"), null);
});
