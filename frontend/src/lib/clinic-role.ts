export function clinicRoleLabel(role: string) {
  switch (role) {
    case "OWNER": return "Gestor (proprietário)";
    case "MANAGER": return "Gestor";
    case "ATTENDANT": return "Atendente";
    case "VIEWER": return "Somente leitura";
    default: return role;
  }
}
