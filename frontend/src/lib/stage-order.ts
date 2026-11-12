export function moveStageInOrder<T>(stages: readonly T[], from: number, to: number) {
  if (from === to || from < 0 || to < 0 || from >= stages.length || to >= stages.length) return stages;
  const next = [...stages];
  const [moved] = next.splice(from, 1);
  next.splice(to, 0, moved);
  return next;
}
