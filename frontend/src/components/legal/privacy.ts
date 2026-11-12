import { LEGAL_ENTITY as E, type LegalDocument } from "./legal-entity";

/** Draft privacy policy (docs/legal/README.md). Pending legal review before the pilot (CRM-073). */
export const PRIVACY: LegalDocument = {
  title: "Política de privacidade",
  description: `Como o ${E.product} trata dados pessoais de usuários e dos contatos das clínicas.`,
  intro: `Esta política explica quais dados pessoais o ${E.product} trata, para quê, com quem são compartilhados e como exercer seus direitos, conforme a Lei Geral de Proteção de Dados (Lei nº 13.709/2018, LGPD). O ${E.product} é um sistema de relacionamento (CRM) e atendimento por WhatsApp para clínicas, oferecido por ${E.company}, CNPJ ${E.cnpj}, com sede em ${E.address}.`,
  sections: [
    {
      id: "papeis",
      title: "Quem decide sobre os dados",
      blocks: [
        "Cada clínica que usa o sistema decide quais dados de seus pacientes e contatos registra e para quê. Por isso, a clínica é a controladora desses dados.",
        `${E.company} trata esses dados em nome da clínica, apenas para prestar o serviço contratado. Nesse papel, é operadora.`,
        `Sobre os dados das pessoas que usam o sistema (equipes das clínicas e das agências parceiras), ${E.company} é a controladora.`,
      ],
    },
    {
      id: "dados",
      title: "Dados que tratamos",
      blocks: [
        "Usuários do sistema:",
        [
          "nome, e-mail e senha (guardada apenas como resumo criptográfico, nunca em texto);",
          "dados do segundo fator de autenticação, quando ativado;",
          "clínica, papel e permissões de cada pessoa;",
          "registros de acesso, como data, hora e endereço IP, para segurança.",
        ],
        "Contatos e pacientes registrados pelas clínicas:",
        [
          "nome, telefone, e-mail, data de nascimento e observações;",
          "preferências de comunicação e o histórico de consentimento;",
          "oportunidades (leads), etapa do funil, temperatura, responsável e motivo de perda;",
          "origem do contato, inclusive dados de anúncios do Meta (como o identificador do anúncio e do clique) ou de códigos de campanha;",
          "vendas registradas (procedimento, valor, data, profissional e forma de pagamento), tarefas e notas;",
          "quando o WhatsApp estiver conectado, as mensagens, mídias e dados de perfil trocados com a clínica.",
        ],
        "O sistema não é prontuário eletrônico. As clínicas são orientadas a não registrar diagnósticos, prescrições ou outros dados de saúde além do estritamente necessário ao relacionamento comercial.",
      ],
    },
    {
      id: "finalidades",
      title: "Para que usamos os dados",
      blocks: [
        [
          "Prestar o serviço: cadastro de contatos, funil de vendas, tarefas, atendimento por WhatsApp, relatórios e notificações (execução de contrato).",
          "Manter as contas seguras: autenticação, prevenção de fraude e de acesso indevido, auditoria (legítimo interesse e cumprimento de obrigação legal).",
          "Enviar e-mails de serviço, como convites, recuperação de senha e avisos de segurança (execução de contrato).",
          "Cumprir obrigações legais e responder a autoridades (cumprimento de obrigação legal).",
        ],
        "Para os dados dos contatos das clínicas, a base legal é definida por cada clínica, como controladora. Não usamos esses dados para finalidades próprias, não os vendemos e não os usamos para publicidade.",
      ],
    },
    {
      id: "whatsapp",
      title: "WhatsApp e Meta",
      blocks: [
        "A integração usa exclusivamente a WhatsApp Business Platform oficial da Meta. Quando uma clínica conecta seu número, as mensagens trocadas com os contatos passam pela infraestrutura da Meta e estão sujeitas também aos termos e à política de privacidade do WhatsApp e da Meta.",
        "Recebemos da Meta, em nome da clínica, as mensagens, mídias, dados de perfil do contato e, quando a conversa começa por um anúncio, os dados de origem desse anúncio. Esses dados são usados apenas para o atendimento e para o registro da origem do contato.",
        "O envio de mensagens segue as regras da Meta, inclusive a janela de 24 horas e os modelos aprovados. Mensagens automáticas e campanhas não fazem parte do serviço atual.",
      ],
    },
    {
      id: "compartilhamento",
      title: "Com quem compartilhamos",
      blocks: [
        "Compartilhamos dados apenas com fornecedores necessários à operação do serviço, contratados para tratá-los em nosso nome:",
        [
          "Amazon Web Services (hospedagem do sistema e dos bancos de dados, na região de São Paulo);",
          "Vercel (hospedagem da interface web);",
          "Resend (envio de e-mails de serviço);",
          "Meta (WhatsApp Business Platform, quando a clínica conecta seu número).",
        ],
        "Alguns desses fornecedores podem tratar dados fora do Brasil. Nesses casos, adotamos as salvaguardas previstas na LGPD, como cláusulas contratuais.",
        "Também podemos compartilhar dados quando exigido por lei ou por ordem de autoridade competente.",
      ],
    },
    {
      id: "seguranca",
      title: "Como protegemos os dados",
      blocks: [
        [
          "Cada clínica tem um banco de dados próprio e isolado, com credenciais exclusivas.",
          "Comunicação criptografada (HTTPS) entre o navegador e o sistema.",
          "Senhas guardadas apenas como resumo criptográfico e segundo fator de autenticação para administradores.",
          "Acesso por papéis, com o mínimo de permissões necessário para cada função.",
          "Registros técnicos sem conteúdo de mensagens, senhas ou outros dados sensíveis.",
        ],
        "Nenhuma medida é infalível. Se ocorrer um incidente de segurança relevante, avisaremos as clínicas afetadas e, quando exigido, a Autoridade Nacional de Proteção de Dados (ANPD).",
      ],
    },
    {
      id: "retencao",
      title: "Por quanto tempo guardamos",
      blocks: [
        "Os dados ficam guardados enquanto a clínica mantiver o contrato. A clínica pode arquivar ou pedir a exclusão de contatos a qualquer momento.",
        `Após o fim do contrato, os dados da clínica são excluídos ou anonimizados em até ${E.retentionDays} dias, salvo quando a lei exigir que sejam mantidos por mais tempo. Antes disso, a clínica pode solicitar a exportação dos seus dados.`,
        "Registros de acesso são mantidos pelo prazo exigido pelo Marco Civil da Internet.",
      ],
    },
    {
      id: "direitos",
      title: "Seus direitos",
      blocks: [
        "A LGPD garante, entre outros, os direitos de confirmar se tratamos seus dados, acessá-los, corrigi-los, pedir anonimização, bloqueio ou exclusão, portabilidade, informação sobre compartilhamentos e revisão do consentimento.",
        "Se você é paciente ou contato de uma clínica, faça o pedido diretamente à clínica, que é a controladora dos seus dados. Nós a apoiamos no atendimento.",
        `Se você usa o sistema, escreva para ${E.privacyEmail}. Respondemos em até 15 dias.`,
        "Você também pode apresentar reclamação à ANPD.",
      ],
    },
    {
      id: "cookies",
      title: "Cookies",
      blocks: [
        "Usamos apenas cookies essenciais: o cookie de sessão, que mantém você conectado, e o de proteção contra falsificação de solicitações (CSRF). Não usamos cookies de publicidade nem de rastreamento.",
      ],
    },
    {
      id: "menores",
      title: "Crianças e adolescentes",
      blocks: [
        "O sistema é destinado a profissionais e empresas. Se a clínica registrar dados de crianças ou adolescentes, ela é responsável por fazê-lo no melhor interesse deles e conforme a lei.",
      ],
    },
    {
      id: "contato",
      title: "Encarregado e contato",
      blocks: [
        `Encarregado pelo tratamento de dados pessoais: ${E.dataOfficer}, pelo e-mail ${E.privacyEmail}.`,
        "Podemos atualizar esta política. Mudanças relevantes serão comunicadas às clínicas antes de entrarem em vigor.",
      ],
    },
  ],
};
