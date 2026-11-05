export const navigation = [
  { href: "#demonstracao", label: "Explore o CRM" },
  { href: "#jornada", label: "A jornada" },
  { href: "#simulador", label: "Simule um cenário" },
];
export const questions = [
  {
    question: "Esta demonstração usa dados reais?",
    answer:
      "Não. Os contatos, valores e atividades são fictícios e existem somente nesta página. A demonstração não acessa bancos de clínicas, não salva alterações e não envia mensagens.",
  },
  {
    question: "O produto já está disponível?",
    answer:
      "O Branly está em desenvolvimento. Esta página permite explorar sua proposta; cadastro público, teste comercial e contratação ainda não estão disponíveis.",
  },
  {
    question: "As automações já estão implementadas?",
    answer:
      "Não. O módulo demonstrativo mostra uma ideia de evolução. Automações e follow-ups estão planejados para uma fase posterior e não fazem parte da entrega inicial do produto.",
  },
  {
    question: "O simulador mostra resultados do Branly?",
    answer:
      "Não. Ele calcula um cenário matemático a partir dos valores e das taxas que você escolhe. Não utiliza resultados de clientes, não prevê desempenho e não garante aumento de receita.",
  },
  {
    question: "O Branly substitui um prontuário?",
    answer:
      "O foco é o relacionamento comercial: contatos, oportunidades, tarefas e avaliações. Diagnóstico, prescrição e prontuário não fazem parte do escopo.",
  },
];
export const journeySteps = [
  {
    title: "A primeira conversa",
    detail:
      "Um novo contato chega à clínica. Origem e interesse dão contexto para começar.",
    state: "Novo contato",
    action: "Registrar origem e interesse",
    field: "Contexto para a equipe",
  },
  {
    title: "Uma oportunidade",
    detail:
      "O interesse ganha uma etapa no funil e uma pessoa responsável pelo acompanhamento.",
    state: "Em atendimento",
    action: "Definir o próximo passo",
    field: "Responsabilidade definida",
  },
  {
    title: "A equipe acompanha",
    detail:
      "Histórico e tarefas ajudam a dar continuidade, mesmo quando outra pessoa assume a conversa.",
    state: "Em acompanhamento",
    action: "Consultar o histórico",
    field: "Informação compartilhada",
  },
  {
    title: "O encontro acontece",
    detail:
      "A avaliação entra na agenda. O combinado e o comparecimento fazem parte da mesma jornada.",
    state: "Avaliação agendada",
    action: "Acompanhar a avaliação",
    field: "Agenda conectada à conversa",
  },
  {
    title: "O vínculo continua",
    detail:
      "Depois da avaliação, a equipe mantém o contexto para uma próxima conversa e novas oportunidades.",
    state: "Relacionamento",
    action: "Planejar o próximo contato",
    field: "Continuidade no atendimento",
  },
];
