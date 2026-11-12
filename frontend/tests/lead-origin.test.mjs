import assert from "node:assert/strict";
import { test } from "node:test";
import { isOriginType, originTypeLabel, originTypes, parseOriginCode } from "../src/lib/lead-origin.ts";

test("accepts the optional origin code only with the API alphabet and length", () => {
  assert.equal(parseOriginCode("  campanha-01_TEST  "), "campanha-01_TEST");
  assert.equal(parseOriginCode(" "), null);
  assert.equal(parseOriginCode("A".repeat(32)), "A".repeat(32));
  for (const invalid of ["a b", "A".repeat(33), "ação", "abc.def", "abc/def"]) {
    assert.equal(parseOriginCode(invalid), undefined);
  }
});

test("uses the contract's unidentified origin consistently", () => {
  assert.equal(originTypes.length, 6);
  assert.ok(isOriginType("UNIDENTIFIED"));
  assert.equal(originTypeLabel("UNIDENTIFIED"), "Não identificada");
  assert.equal(originTypeLabel("toString"), "Origem indisponível");
});
