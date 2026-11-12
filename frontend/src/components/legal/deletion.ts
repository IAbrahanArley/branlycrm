import { LEGAL_ENTITY as E, type LegalDocument } from "./legal-entity";

/** Data deletion instructions, the page the Meta app settings link to (docs/legal/README.md). */
export const DELETION: LegalDocument = {
  title: "Exclusão de dados",
  description: `Como pedir a exclusão de dados pessoais tratados pelo ${E.product}.`,
  intro: `Esta página explica como pedir a exclusão dos seus dados pessoais tratados pelo ${E.product}, inclusive os recebidos pelo WhatsApp por meio da plataforma da Meta.`,
  sections: [
    {
      id: "contato-da-clinica",
      title: "Se você é paciente ou contato de uma clínica",
      blocks: [
        "Seus dados foram registrados pela clínica com quem você falou, que é a responsável por eles. Peça a exclusão diretamente à clínica, por qualquer canal dela, inclusive pelo próprio WhatsApp.",
        "A clínica exclui ou anonimiza os seus dados no sistema. Se você não conseguir falar com a clínica, escreva para o e-mail abaixo informando o nome da clínica e o seu telefone, e encaminharemos o pedido a ela.",
      ],
    },
    {
      id: "usuario",
      title: "Se você usa o sistema",
      blocks: [
        `Envie o pedido para ${E.privacyEmail} a partir do e-mail da sua conta. Confirmaremos sua identidade e excluiremos sua conta e seus dados de acesso em até 15 dias, mantendo apenas o que a lei exigir, como registros de acesso pelo prazo legal.`,
        "Registros que você criou dentro de uma clínica, como notas e vendas, pertencem à clínica e continuam com ela, sem os seus dados de acesso.",
      ],
    },
    {
      id: "clinica",
      title: "Se você representa uma clínica",
      blocks: [
        [
          "Para desconectar o WhatsApp, use a configuração do canal no sistema ou remova o acesso do app nas configurações da sua conta do WhatsApp Business na Meta.",
          `Para excluir todos os dados da clínica ao encerrar o contrato, faça o pedido pelo e-mail ${E.privacyEmail}. A exclusão é concluída em até ${E.retentionDays} dias, depois da exportação, se você a pedir.`,
        ],
      ],
    },
    {
      id: "duvidas",
      title: "Dúvidas",
      blocks: [`Encarregado pelo tratamento de dados: ${E.dataOfficer}, pelo e-mail ${E.privacyEmail}.`],
    },
  ],
};
