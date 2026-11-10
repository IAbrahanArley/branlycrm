const requestKey = (subjectId: string, agencyId: string) => `branly:clinic-request:${subjectId}:${agencyId}`;

export function readClinicRequest(subjectId: string, agencyId: string, storage: Pick<Storage, "getItem">) {
  const value = storage.getItem(requestKey(subjectId, agencyId));
  return value && /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(value) ? value : undefined;
}

export function writeClinicRequest(subjectId: string, agencyId: string, requestId: string | undefined, storage: Pick<Storage, "setItem" | "removeItem">) {
  const key = requestKey(subjectId, agencyId);
  if (requestId) storage.setItem(key, requestId);
  else storage.removeItem(key);
}
