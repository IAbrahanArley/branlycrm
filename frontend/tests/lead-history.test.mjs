import assert from "node:assert/strict";
import { test } from "node:test";
import { leadHistoryEntries } from "../src/lib/api/lead-history.ts";

const event = (id, occurredAt) => ({ id, occurredAt, actorId: "user-a", type: "CREATED" });
const note = (id, createdAt) => ({ id, createdAt, authorId: "user-b", body: "Retornar amanhã." });

test("interleaves events and notes without losing sub-millisecond precision", () => {
  const events = [event("first", "2026-11-11T10:00:00Z"), event("third", "2026-11-11T10:00:00.000200Z")];
  const notes = [note("second", "2026-11-11T10:00:00.000100Z")];
  const entries = leadHistoryEntries(events, notes);

  assert.deepEqual(entries.map((entry) => entry.kind === "note" ? entry.note.id : entry.event.id), ["first", "second", "third"]);
  assert.deepEqual(entries.map(({ actorId }) => actorId), ["user-a", "user-b", "user-a"]);
});

test("keeps the API order for ties within each source, with events before notes", () => {
  const instant = "2026-11-11T10:00:00Z";
  const events = [event("z", instant), event("a", instant)];
  const notes = [note("n", "2026-11-11T10:00:00.000000Z")];

  assert.deepEqual(leadHistoryEntries(events, notes).map((entry) => entry.kind === "note" ? entry.note.id : entry.event.id), ["z", "a", "n"]);
  assert.deepEqual(leadHistoryEntries([], []), []);
});
