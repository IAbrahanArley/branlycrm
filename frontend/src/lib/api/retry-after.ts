export function loginRetryDeadline(header: string | null, now: number) {
  const value = header?.trim() ?? "";
  if (/^\d+$/.test(value)) {
    const deadline = now + Number(value) * 1000;
    if (Number.isSafeInteger(deadline)) return deadline;
  }

  if (/[a-z]/i.test(value)) {
    const deadline = Date.parse(value);
    if (Number.isFinite(deadline)) return Math.max(now, deadline);
  }

  return now + 60_000;
}
