import { LEGAL_ENTITY as E, type LegalDocument } from "./legal-entity";

/** Draft terms of use (docs/legal/README.md). Pending legal review and the billing decision. */
export const TERMS: LegalDocument = {
  title: "Termos de uso",
  description: `Condições de uso do ${E.product} por clínicas e agências parceiras.`,
  intro: `Estes termos regulam o uso do ${E.product}, oferecido por ${E.company}, CNPJ ${E.cnpj}. Ao criar uma conta ou usar o sistema, a clínica, a agência parceira e cada usuário concordam com eles e com a Política de privacidade.`,
  sections: [
    {
      id: "servico",
      title: "O serviço",
      blocks: [
        `O ${E.product} é um sistema de CRM e atendimento por WhatsApp para clínicas: cadastro de contatos, funil de vendas, registro de vendas, tarefas, origem dos contatos, relatórios e, quando conectado, a caixa de entrada do WhatsApp.`,
        "O sistema não é prontuário eletrônico e não fornece orientação clínica, diagnóstico ou prescrição.",
        "Valores de venda mostrados no sistema são os registrados pela equipe da clínica e não substituem a contabilidade nem comprovam recebimento.",
      ],
    },
    {
      id: "contas",
      title: "Contas e acesso",
      blocks: [
        [
          "Cada usuário tem uma conta pessoal e intransferível e é responsável por manter sua senha e seu segundo fator em segurança.",
          "A clínica define quem da sua equipe tem acesso e com qual papel, e deve remover o acesso de quem deixar a equipe.",
          "A agência parceira só acessa as clínicas que a autorizarem, nos limites dessa autorização.",
        ],
      ],
    },
    {
      id: "responsabilidades",
      title: "Responsabilidades da clínica",
      blocks: [
        [
          "Ter base legal para os dados que registra e informar seus contatos sobre o tratamento, como controladora (LGPD).",
          "Registrar apenas o necessário ao relacionamento, sem dados de saúde além do indispensável.",
          "Respeitar as preferências de comunicação e os pedidos de descadastro dos contatos.",
          "Cumprir as políticas do WhatsApp e da Meta, inclusive as regras de mensagens comerciais.",
          "Manter a veracidade das informações e das vendas registradas.",
        ],
      ],
    },
    {
      id: "uso",
      title: "Uso aceitável",
      blocks: [
        "Não é permitido usar o sistema para:",
        [
          "enviar mensagens não solicitadas em massa (spam) ou contrárias às políticas do WhatsApp;",
          "tratar dados de forma ilegal ou sem base legal;",
          "tentar acessar dados de outras clínicas, contornar controles de acesso ou prejudicar a operação do sistema;",
          "copiar, revender ou fazer engenharia reversa do sistema.",
        ],
        "O descumprimento pode levar à suspensão do acesso, com aviso sempre que possível.",
      ],
    },
    {
      id: "whatsapp",
      title: "WhatsApp",
      blocks: [
        "A conexão do número usa a WhatsApp Business Platform oficial da Meta. O número e a conta do WhatsApp Business continuam pertencendo à clínica.",
        "As conversas cobradas pela Meta são pagas conforme a tabela da Meta, pelo meio de pagamento cadastrado na conta do WhatsApp Business da clínica, salvo acordo diferente por escrito.",
        "A disponibilidade, as regras e os preços do WhatsApp dependem da Meta, que pode alterá-los ou suspender números por violação às suas políticas.",
      ],
    },
    {
      id: "dados",
      title: "Dados da clínica",
      blocks: [
        "Os dados que a clínica registra pertencem a ela. Nós os tratamos apenas para prestar o serviço, conforme a Política de privacidade.",
        "A clínica pode exportar seus dados enquanto o contrato estiver ativo e pedir a exclusão ao encerrá-lo.",
      ],
    },
    {
      id: "disponibilidade",
      title: "Disponibilidade e suporte",
      blocks: [
        `Buscamos manter o sistema disponível e seguro, mas podem ocorrer interrupções para manutenção ou por falhas de terceiros. O suporte é prestado pelo e-mail ${E.supportEmail}.`,
        "Funcionalidades em fase piloto podem mudar ou ser descontinuadas, com aviso prévio às clínicas que as usam.",
      ],
    },
    {
      id: "pagamento",
      title: "Planos e pagamento",
      blocks: [
        "Preços, planos, forma de cobrança e reajustes são definidos na proposta comercial ou no contrato de cada clínica ou agência parceira.",
      ],
    },
    {
      id: "responsabilidade",
      title: "Limitação de responsabilidade",
      blocks: [
        "Não respondemos por decisões clínicas ou comerciais tomadas com base no sistema, por conteúdo inserido pelos usuários, nem por indisponibilidade ou mudanças causadas pela Meta ou por outros terceiros, nos limites permitidos pela lei.",
      ],
    },
    {
      id: "encerramento",
      title: "Suspensão e encerramento",
      blocks: [
        "A clínica pode encerrar o uso conforme o seu contrato. Podemos suspender ou encerrar o acesso em caso de violação destes termos ou da lei.",
        `Após o encerramento, os dados seguem o prazo de exclusão da Política de privacidade (${E.retentionDays} dias), salvo obrigação legal.`,
      ],
    },
    {
      id: "gerais",
      title: "Disposições gerais",
      blocks: [
        "Podemos atualizar estes termos. Mudanças relevantes serão comunicadas com antecedência, e o uso continuado depois delas significa concordância.",
        `Estes termos seguem a lei brasileira. Fica eleito o foro da ${E.forum} para resolver eventuais disputas.`,
      ],
    },
  ],
};
