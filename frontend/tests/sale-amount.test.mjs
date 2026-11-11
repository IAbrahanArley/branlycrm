import assert from "node:assert/strict";
import { test } from "node:test";
import { formatSaleAmount, parseSaleAmount } from "../src/lib/sale-amount.ts";

test("converts common BRL input to exact integer cents", () => {
  for (const [input, cents] of [["250", 25000], ["250,01", 25001], [" 12,3 ", 1230], ["1.234,56", 123456], ["12.05", 1205], ["0,01", 1], ["10.000.000,00", 1_000_000_000]]) {
    assert.equal(parseSaleAmount(input), cents, input);
  }
});

test("rejects ambiguous, fractional-cent, non-positive and out-of-range input", () => {
  for (const input of ["", "1.234", "1,234", "0", "-1", "1e3", "10.000.000,01", "99999999999999999999", "1.00,50"]) {
    assert.equal(parseSaleAmount(input), null, input);
  }
});

test("preserves every cent when formatting aggregated sales amounts", () => {
  assert.equal(formatSaleAmount(0), "R$\u00a00,00");
  assert.equal(formatSaleAmount(1), "R$\u00a00,01");
  assert.equal(formatSaleAmount(Number.MAX_SAFE_INTEGER), "R$\u00a090.071.992.547.409,91");
});
