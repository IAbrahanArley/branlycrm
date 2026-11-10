const messages: Record<string, string> = {
  AGENCY_NOT_FOUND: "A agência não está mais disponível para sua conta.",
  CLINIC_NOT_FOUND: "A clínica não está mais disponível nesta carteira. Atualize a lista.",
  IDEMPOTENCY_KEY_REUSED: "Esta solicitação já foi usada com outros dados. Confira a carteira antes de iniciar um novo cadastro.",
  AGENCY_PENDING_CLINIC_LIMIT: "A agência atingiu o limite de clínicas em preparação. Aguarde a ativação de uma clínica para cadastrar outra.",
  AGENCY_CLINIC_HAS_OWNER: "A clínica já teve um gestor. Convites da equipe são gerenciados no painel da clínica.",
  INVITATION_SEND_LIMIT: "O limite de envio de convites foi atingido. Aguarde antes de reenviar.",
};

export function agencyErrorMessage(code: string, fallback: string) {
  return Object.hasOwn(messages, code) ? messages[code] : fallback;
}

export function ownerDeliveryMessage(delivery: "SENT" | "FAILED" | "NOT_SENT") {
  switch (delivery) {
    case "SENT": return "Convite do gestor enviado.";
    case "FAILED": return "O convite ficou registrado, mas o e-mail não foi enviado. Use Reemitir convite na carteira.";
    case "NOT_SENT": return "A solicitação já havia sido processada. Nenhum novo e-mail foi enviado.";
  }
}
