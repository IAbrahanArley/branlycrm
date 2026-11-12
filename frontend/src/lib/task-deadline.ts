function localFormatter(timeZone: string) {
  return new Intl.DateTimeFormat("sv-SE", {
    timeZone, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23",
  });
}

export function taskLocalDeadline(iso: string, timeZone: string) {
  return localFormatter(timeZone).format(new Date(iso)).replace(" ", "T");
}

export function taskDeadlineInstant(local: string, timeZone: string) {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(local)) return null;
  const wallTime = Date.parse(`${local}:00Z`);
  if (!Number.isFinite(wallTime)) return null;
  const formatter = localFormatter(timeZone);
  const offsets = new Set<number>();
  const dayMs = 86_400_000;
  // Probe both sides of a possible clock change and validate the resulting local time.
  for (const probe of [wallTime - dayMs, wallTime, wallTime + dayMs]) {
    const clock = formatter.format(new Date(probe)).replace(" ", "T");
    offsets.add(Date.parse(`${clock}:00Z`) - probe);
  }
  const matches = [...offsets].map((offset) => wallTime - offset)
    .filter((instant) => formatter.format(new Date(instant)).replace(" ", "T") === local);
  if (matches.length !== 1 || matches[0] < Date.UTC(2000, 0, 1) || matches[0] >= Date.UTC(2100, 0, 1)) return null;
  return new Date(matches[0]).toISOString();
}
