import { test } from "node:test";
import assert from "node:assert/strict";
import { calculateScenario } from "../src/components/landing/simulator.ts";
test("computes explicit baseline, hypothetical revenue and difference in cents", () => {
  const value = calculateScenario({
    leads: 100,
    conversion: 10,
    targetConversion: 15,
    ticket: 500,
  });
  assert.equal(value.currentRevenueCents, 500000);
  assert.equal(value.targetRevenueCents, 750000);
  assert.equal(value.differenceCents, 250000);
});
test("supports lower hypothetical rates without inventing positive gains", () => {
  assert.equal(
    calculateScenario({
      leads: 100,
      conversion: 20,
      targetConversion: 10,
      ticket: 500,
    }).differenceCents,
    -500000,
  );
});
test("zero inputs and equal rates yield no additional revenue", () => {
  assert.equal(
    calculateScenario({
      leads: 0,
      conversion: 10,
      targetConversion: 15,
      ticket: 500,
    }).differenceCents,
    0,
  );
  assert.equal(
    calculateScenario({
      leads: 100,
      conversion: 15,
      targetConversion: 15,
      ticket: 0,
    }).differenceCents,
    0,
  );
});
test("rounds ticket to integer cents and rejects invalid assumptions", () => {
  assert.equal(
    calculateScenario({
      leads: 100,
      conversion: 100,
      targetConversion: 100,
      ticket: 1.01,
    }).currentRevenueCents,
    10100,
  );
  for (const patch of [
    { leads: -1 },
    { leads: 1.5 },
    { conversion: 101 },
    { targetConversion: -1 },
    { ticket: NaN },
    { ticket: -2 },
  ])
    assert.throws(
      () =>
        calculateScenario({
          leads: 100,
          conversion: 10,
          targetConversion: 15,
          ticket: 500,
          ...patch,
        }),
      RangeError,
    );
});
