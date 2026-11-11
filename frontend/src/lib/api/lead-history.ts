import type { LeadEvent, LeadNote } from "./leads.ts";

export function leadHistoryEntries(events: LeadEvent[], notes: LeadNote[]) {
  const entries = [
    ...events.map((event) => ({ kind: "event" as const, at: event.occurredAt, actorId: event.actorId, event })),
    ...notes.map((note) => ({ kind: "note" as const, at: note.createdAt, actorId: note.authorId, note })),
  ];

  // A API usa Instant. Date.parse descarta a precisão abaixo do milissegundo.
  const dated = entries.map((entry) => ({
    entry,
    milliseconds: Date.parse(entry.at),
    fraction: (entry.at.match(/\.(\d+)/)?.[1] ?? "").padEnd(9, "0"),
  }));

  // O sort estável preserva a ordem da API em cada lista. Eventos precedem notas no empate.
  return dated.sort((a, b) =>
    a.milliseconds - b.milliseconds || a.fraction.localeCompare(b.fraction),
  ).map(({ entry }) => entry);
}
