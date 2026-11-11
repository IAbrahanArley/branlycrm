import assert from "node:assert/strict";
import { test } from "node:test";
import { parseCatalogPrice } from "../src/lib/catalog-input.ts";

test("converts reference prices to integer cents and accepts zero", () => {
  for (const [input, cents] of [["0", 0], ["12,30", 1230], [" 12.30 ", 1230], ["1.234,56", 123456], ["10.000.000,00", 1_000_000_000]]) {
    assert.equal(parseCatalogPrice(input), cents, input);
  }
});

test("rejects fractional cents, ambiguous grouping, negatives and values above the API limit", () => {
  for (const input of ["", "-10", "1.234", "1,234", "1e2", "10.000.000,01", "99999999999999999999"]) {
    assert.equal(parseCatalogPrice(input), null, input);
  }
});
