const messages: Record<string, string> = {
  MEMBER_CHANGE_FORBIDDEN: "Seu papel não permite alterar este membro ou alterar sua própria participação.",
  MEMBER_NOT_FOUND: "O membro não está mais ativo nesta clínica. Atualize a lista.",
  LAST_OWNER: "A clínica precisa manter um proprietário ativo. Defina outro proprietário antes de continuar.",
  INVITATION_FORBIDDEN: "Seu papel não permite gerenciar convites com esta função.",
  MEMBER_ALREADY_ACTIVE: "Este e-mail já pertence a um membro ativo da clínica.",
  INVITATION_NOT_FOUND: "Este convite não está mais disponível. Atualize a lista.",
  INVITATION_SEND_LIMIT: "O limite de envio de convites foi atingido. Aguarde antes de tentar novamente.",
};

export function teamErrorMessage(code: string, fallback: string) {
  return Object.hasOwn(messages, code) ? messages[code] : fallback;
}

export function invitationDeliveryMessage(delivery: "SENT" | "FAILED") {
  return delivery === "SENT"
    ? "Convite registrado e e-mail enviado."
    : "O convite ficou pendente, mas o e-mail não foi enviado. Use Reenviar na lista.";
}
