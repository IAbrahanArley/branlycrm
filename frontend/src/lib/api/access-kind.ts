export function accessKind(clinicCount: number, agencyCount: number) {
  if (clinicCount && agencyCount) return "both";
  if (clinicCount) return "clinic";
  if (agencyCount) return "agency";
  return "none";
}
