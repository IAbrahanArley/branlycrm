import assert from "node:assert/strict";
import { test } from "node:test";
import { saleValuesChanged } from "../src/lib/sale-change.ts";

const sale = { procedureId: "p1", professionalId: null, amountCents: 23000, soldOn: "2026-11-10", paymentMethod: "PIX" };

test("a reason by itself does not constitute a sale correction", () => {
  assert.equal(saleValuesChanged(sale, { ...sale, reason: "Conferência" }), false);
});

test("a changed amount, date or optional selection constitutes a correction", () => {
  assert.equal(saleValuesChanged(sale, { ...sale, amountCents: 23001 }), true);
  assert.equal(saleValuesChanged(sale, { ...sale, soldOn: "2026-11-09" }), true);
  assert.equal(saleValuesChanged(sale, { ...sale, paymentMethod: null }), true);
  assert.equal(saleValuesChanged(sale, { ...sale, professionalId: "p2" }), true);
});
