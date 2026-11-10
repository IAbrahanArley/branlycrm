import assert from "node:assert/strict";
import { test } from "node:test";
import { funnelErrorMessage, previewStageMove } from "../src/lib/api/funnel-view.ts";

const lead = { id: "lead-a", stageId: "new", temperature: "HOT", version: 4 };
const source = { items: [lead], page: 1, pageSize: 20, total: 1 };
const target = { items: [], page: 1, pageSize: 20, total: 0 };

test("previews a versioned move without changing the original pages used for rollback", () => {
  assert.deepEqual(previewStageMove(source, lead, "new", "service", "new"), { ...source, items: [], total: 0 });
  assert.deepEqual(previewStageMove(target, lead, "new", "service", "service"), {
    ...target, items: [{ ...lead, stageId: "service" }], total: 1,
  });
  assert.deepEqual(source.items, [lead]);
  assert.equal(lead.stageId, "new");
  assert.equal(previewStageMove(target, lead, "new", "service", "closed"), target);
});

test("updates the total without inserting the card in later destination pages", () => {
  const later = { items: [], page: 2, pageSize: 20, total: 20 };
  assert.deepEqual(previewStageMove(later, lead, "new", "service", "service"), { ...later, total: 21 });
});

test("explains a version conflict without treating server failures as successful moves", () => {
  assert.match(funnelErrorMessage("LEAD_STALE", "erro"), /Atualize o funil/);
  assert.equal(funnelErrorMessage("constructor", "mensagem da API"), "mensagem da API");
});
