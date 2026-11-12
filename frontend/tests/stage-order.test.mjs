import assert from "node:assert/strict";
import { test } from "node:test";
import { moveStageInOrder } from "../src/lib/stage-order.ts";

test("inserts an open stage at the target position while retaining the other stages", () => {
  const stages = ["new", "service", "scheduled", "attended"];
  assert.deepEqual(moveStageInOrder(stages, 0, 3), ["service", "scheduled", "attended", "new"]);
  assert.deepEqual(moveStageInOrder(stages, 3, 1), ["new", "attended", "service", "scheduled"]);
  assert.deepEqual(moveStageInOrder(stages, 2, 1), ["new", "scheduled", "service", "attended"]);
  assert.deepEqual(stages, ["new", "service", "scheduled", "attended"]);
});

test("keeps the same order at a boundary or when dropped in place", () => {
  const stages = ["new", "service"];
  assert.equal(moveStageInOrder(stages, 0, -1), stages);
  assert.equal(moveStageInOrder(stages, 1, 2), stages);
  assert.equal(moveStageInOrder(stages, 1, 1), stages);
});
