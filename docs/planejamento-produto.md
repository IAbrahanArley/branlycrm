# Planejamento do Produto — Branly CRM

> **Status:** reescrito em 06/11/2026 a partir do documento do cliente [Requisitos da Plataforma v1.1](requisitos/requisitos-plataforma-clinicas-v1.1.pdf) (ADR-024). É a fonte de escopo e substitui a versão de 05/11/2026 (ADR-019).
> Ordem e estado de execução: [TASKS.md](../TASKS.md). Regras dos agentes: [AGENTS.md](../AGENTS.md). Decisões: [adr/decisoes.md](adr/decisoes.md).
> **Como ler os IDs:**
> - **De duas casas** (`PAC-01`, `CRM-05`, `ORI-02`, `WPP-03`, `RN-01`...) são requisitos do documento do cliente.
> - **De três casas** (`CRM-012`, `CRM-082`) são tarefas do backlog.

## 1. Visão do produto

- **Tipo:** SaaS B2B multi-clínica de CRM, atendimento por WhatsApp e, a partir da Fase 2, automação com IA.
- **Segmentos:** clínicas de estética, odontologia e saúde.
- **Foco:** relacionamento e vendas, nunca prontuário.
- **Ideia central:** o paciente clica no anúncio e chama no WhatsApp da clínica. Na mesma hora fica registrado como lead com a origem "Tráfego pago". A partir daí a equipe atende (na Fase 2, também a IA), o lead avança no funil e o gestor é avisado dos eventos importantes.

### Dois modos de venda

| Modo | Quem contrata | Como a clínica entra | Fase |
|---|---|---|---|
| **Agência parceira** | Assessoria de marketing que revende o sistema às clínicas da sua carteira, junto com o tráfego | A agência cria a clínica, convida o gestor e conecta o WhatsApp | F1 (é o primeiro cliente) |
| **Clínica direta** | A própria clínica | No piloto, criada pela plataforma; depois, por cadastro público com verificação de e-mail | F1 pela plataforma; autosserviço antes de abrir venda direta |

Nos dois modos a clínica é o mesmo tenant isolado, com o mesmo produto. A agência é só uma camada de gestão de carteira por cima (ADR-026).

### Fora do escopo (decisão do cliente e nossa)

- **Métricas de anúncios:** gasto, CPL, CAC, ROAS, ROI e desempenho de campanha. O tráfego aparece só como origem do lead. Não há integração com a API de anúncios da Meta ou do Google.
- **Clínico e financeiro:** prontuário, diagnóstico, prescrição, orientação clínica, estoque, convênios e financeiro clínico.
- **WhatsApp não oficial** (conexão por QR Code), pelo risco de banimento do número da clínica.
- **Agenda própria** com controle de conflito de horário. O funil tem as etapas Agendado e Compareceu, e a agenda integrada é só sugestão (ADR-024).

## 2. Níveis de acesso e perfis

| Nível | Perfil | O que faz |
|---|---|---|
| Plataforma | **Super Admin** (equipe Branly) | Agências, clínicas diretas, planos, suspensão e suporte auditado. Não lê dados de pacientes por padrão. |
| Agência | **Admin da agência** | Vê a carteira, cria clínicas nela, convida o gestor de cada uma e conecta o WhatsApp. Na F2 também liga módulos e vê o painel consolidado. |
| Clínica | **Gestor** (`OWNER`, `MANAGER`) | Acompanha dashboard e relatório mensal, define regras e etapas, gerencia a equipe e recebe alertas de venda. |
| Clínica | **Atendente** (`ATTENDANT`) | Responde conversas, cadastra contatos, move leads, define temperatura, cria tarefas e registra vendas e perdas. |
| Clínica | **Somente leitura** (`VIEWER`) | Consulta contatos, leads, conversas e relatórios sem alterar nada. |
| — | Paciente / lead | Não acessa o sistema; interage só pelo WhatsApp. |

**Gestor:** tem dois papéis no banco. O `OWNER` responde pela clínica (plano, exportação, auditoria, último proprietário) e o `MANAGER` opera o comercial e a equipe. O catálogo substitui os cinco papéis da ADR-020 (ADR-025). A matriz está em [permissoes.md](permissoes.md).

**Regras da agência (ADR-026):**
- Uma clínica pertence a no máximo uma agência. A clínica direta não tem agência.
- O papel de agência dá autoridade só sobre a carteira: criar clínica, convidar o gestor, conectar o WhatsApp e ver o estado (nome, situação, plano, conexão).
- **Dados de pacientes:** uma agência nunca os lê pelo papel de agência. Só os vê por uma participação explícita na clínica, como qualquer usuário. Essa participação é visível ao gestor, auditada e revogável.
- O painel consolidado (REL-07, F2) mostra apenas agregados por clínica, sem dados pessoais.

## 3. Fluxo principal: do anúncio à venda

1. **Anúncio:** a agência publica anúncio "Clique para WhatsApp" ou um link `wa.me` com código de campanha.
2. **Primeira mensagem:** o paciente envia a mensagem ao número da clínica.
3. **Webhook:** a Meta entrega a mensagem ao webhook. A API confere a assinatura, descobre a clínica pelo número e grava o evento uma única vez.
4. **Registro automático:** o sistema procura o contato pelo telefone normalizado. Se não existir, cria o contato e um lead na etapa Novo, com a origem detectada (seção 5.4).
5. **Aviso:** "Novo lead de tráfego: [nome]", com link para a conversa.
6. **Atendimento:**
   - **F1:** atendentes respondem pela caixa de entrada compartilhada.
   - **F2:** a IA responde, qualifica e define a temperatura.
7. **Intervenção humana (F2):** a IA transfere quando não deve ou não consegue continuar, e a equipe é avisada.
8. **Funil:** o lead passa por Em atendimento, Agendado e Compareceu, e termina em Venda fechada (com venda registrada) ou Perdido (com motivo).
9. **Aviso final:** "Venda finalizada" ou "Venda não concluída" ao responsável e ao gestor.
10. **Dados:** tudo alimenta a lista de leads, o dashboard da recepção e o relatório mensal.

## 4. Módulos e fases

| Módulo | F1 (MVP) | F2 (automação e IA) | Sugestões |
|---|---|---|---|
| Acesso, clínicas e agências | ADM-01, 02, 03; carteira da agência; criação de clínica | ADM-04 auditoria completa, 05 módulos, 06 assistente de onboarding | ADM-07 white-label |
| Pacientes e contatos | PAC-01 a 08 | PAC-09 funcionários, 10 segmentos, 11 anonimização | — |
| Funil, leads e vendas | CRM-01 a 09 | CRM-10 rodízio, 11 temperatura pela IA | — |
| Origem do lead | ORI-01 a 05 | ORI-06 cadastro de campanhas | — |
| WhatsApp | WPP-01 a 06 | WPP-07 entrega e leitura, 08 IA↔humano, 09 monitoramento, 10 templates | — |
| IA de atendimento | — | IA-01 a 09 | — |
| Automações e disparos | — | AUT-01 a 08 | AUT-09 lembrete de consulta, 10 editor de regras |
| Notificações | NOT-01 a 03 | NOT-04 preferências, 05 sem resposta, 06 push e e-mail | — |
| Lista e exportação | LST-01, 02 | LST-03 filtros salvos | LST-04 exportação programada |
| Dashboard e relatórios | REL-01, 02 | REL-03 ao vivo, 04 comparativo, 05 atendimento, 06 PDF, 07 painel da agência | REL-08 resumo semanal |
| Administração SaaS | Clínicas, agências, planos e suspensão, de forma manual | Cobrança automatizada | — |

A Fase 3 (escala) junta o que sobrar e as ideias da seção 12 do documento: NPS, programa de indicação, previsão de retorno, score de lead e biblioteca de templates.

## 5. Requisitos da Fase 1 e como serão atendidos

### 5.1 Acesso, clínicas e agências (ADM-01 a 03)

- **Login e segurança:**
  - login por e-mail e senha, recuperação de senha por e-mail (Resend, ADR-021) e convite com validade;
  - **2FA (TOTP)** obrigatório para Super Admin, Admin da agência e `OWNER`; opcional para os demais.
- **Criação de clínicas:**
  - a agência cria clínicas na carteira; a plataforma cria clínicas diretas e agências;
  - a criação usa o provisionador existente (ADR-018), e o primeiro gestor entra por convite.
- **Várias clínicas por usuário:** seleção da clínica ativa (já implementada) e um papel por clínica.
- **Suspensão:** de clínica ou agência, sem perda de dados. Suspender uma agência não apaga as clínicas dela; a política de bloqueio delas fica pendente (seção 13).
- **Isolamento (ADM-01, RN-10):** banco PostgreSQL por clínica, com credencial própria (ADR-014). É mais forte que o "campo de clínica em todas as tabelas" sugerido pelo cliente e atende ao mesmo objetivo.

### 5.2 Pacientes e contatos (PAC-01 a 08)

- **Cadastro e duplicidade:**
  - campos: nome, WhatsApp, e-mail, data de nascimento, origem e observações;
  - telefone normalizado em E.164 e único por clínica (PAC-03).
- **Criação automática (PAC-02):** na primeira mensagem de um número novo, com o nome do perfil do WhatsApp.
- **Lead ou paciente:** a situação não é digitada. "Paciente" é o contato com ao menos uma venda registrada (glossário do documento).
- **Procedimentos (PAC-04):** os contratados ficam no registro de **Venda** (seção 5.3), que alimenta o histórico do paciente.
- **Histórico (PAC-05):** linha do tempo única com conversas, vendas, mudanças de etapa e temperatura, notas e tarefas. Na F2 entram também as mensagens automáticas.
- **Tags manuais (PAC-06).**
- **Importação CSV/XLSX (PAC-07):**
  - validação por linha e relatório de erros;
  - não sobrescreve contatos existentes sem confirmação;
  - o arquivo é apagado depois de processado.
- **Consentimento (PAC-08):** opt-in e opt-out por canal, com data e origem. Cada mudança fica no histórico, e o opt-out sempre prevalece (RN-05).

### 5.3 Funil, leads e vendas (CRM-01 a 09)

- **Funil configurável (CRM-01):**
  - etapas padrão Novo, Em atendimento, Agendado, Compareceu, Venda fechada e Perdido;
  - cada etapa tem um tipo: aberta, ganha ou perdida.
- **Visões (CRM-02):** Kanban (arrastar cartões) e lista.
- **Lead (CRM-03) e regra RN-01:**
  - o lead é a oportunidade, criado automaticamente na etapa Novo;
  - cada contato tem no máximo **um lead aberto**, garantido por índice único parcial;
  - número já conhecido reaproveita o contato e só abre lead novo se o anterior estiver encerrado.
- **Responsável (CRM-04):** atribuição manual. O rodízio automático fica para a F2.
- **Venda (CRM-05):**
  - registro próprio com procedimento e valor (obrigatórios), data, profissional e forma de pagamento (opcionais);
  - valor em centavos inteiros;
  - registrar a venda leva o lead a Venda fechada;
  - só vendas registradas entram no faturamento (RN-04).
- **Perda (CRM-06):** motivo obrigatório, de uma lista configurável por clínica.
- **Tarefas (CRM-07):** tarefas e lembretes de follow-up com data e responsável; atrasadas calculadas no fuso da clínica.
- **Temperatura (CRM-08):** frio, morno, quente e qualificado, alterável manualmente pelos critérios da seção 3.2 do documento. Cada mudança é um evento.
- **Linha do tempo (CRM-09, RN-08):** etapa, temperatura e responsável viram eventos com autor (usuário ou, na F2, IA) e horário.
- **"Profissional" da venda:** é um nome de profissional cadastrado na clínica. Não precisa ter login.

### 5.4 Origem do lead (ORI-01 a 05)

**Valores de origem:** Tráfego pago, Instagram orgânico, Indicação, Google e Outro, mais "Não identificada" para quando a detecção não acha nada (ORI-01).

| Como chega | Detecção | Confiabilidade |
|---|---|---|
| Anúncio "Clique para WhatsApp" da Meta | Bloco `referral` da primeira mensagem no webhook: `source_type`, `source_id` (ID do anúncio), `source_url`, `headline`, `ctwa_clid` | Alta. Não depende do paciente; exige API oficial ou provedor que repasse o campo (ORI-02) |
| Link `wa.me` com código (Google, site, bio, QR) | Padrão `[CODIGO]` na primeira mensagem, ex.: `[BOTOX01]` (ORI-03) | Média. O paciente pode apagar o texto |
| Outros (indicação, balcão, telefone) | Escolha manual do atendente | Depende da equipe |

**Regras:**
- **Primeiro toque fica (RN-02):** a origem é gravada na primeira mensagem do lead e não é sobrescrita automaticamente.
- **Contatos seguintes:** viram eventos de origem do contato, para o histórico ("chegou orgânico, voltou por anúncio").
- **Correção manual:** é permitida e registrada na linha do tempo.
- **Vínculo com a venda (RN-03):** a origem pertence ao lead e acompanha a venda, mesmo depois que o contato vira paciente.
- **Campanha na F1:** o lead guarda o ID e o título do anúncio, ou o código. O cadastro de campanhas com nome e código (ORI-06) é F2. Até lá, os relatórios agrupam pelo código ou anúncio bruto.
- **Selo e filtros (ORI-04 e ORI-05):** selo "Tráfego" na lista, no Kanban e na conversa. Filtros e relatórios por origem só contam leads e vendas, sem custos.
- **Limite conhecido:** quem vê o anúncio e depois chama o número direto entra como orgânico. O sistema não promete atribuição completa.

### 5.5 WhatsApp (WPP-01 a 06)

- **Conexão (WPP-01):** o número da clínica é conectado pela API oficial, pela agência ou pela plataforma, conforme o provedor escolhido (seção 13). Um número por clínica no MVP.
- **Entrada (WPP-02):**
  - webhook público, sem sessão, com verificação da assinatura `X-Hub-Signature-256`;
  - a clínica é resolvida pelo `phone_number_id`, nunca por dado livre da requisição;
  - idempotência pelo ID da mensagem (`wamid`); o webhook responde rápido e o processamento segue em fila.
- **Caixa de entrada (WPP-03):**
  - compartilhada por vários atendentes, com histórico;
  - situações aguardando e em atendimento (na F2 também "com IA");
  - responsável pela conversa e atualização quase em tempo real.
- **Mídias (WPP-04):** texto, imagem, áudio, PDF e documentos, em armazenamento de objetos privado e com links temporários.
- **Respostas rápidas (WPP-05):** configuráveis por clínica.
- **Janela de 24 horas (WPP-06):** fora da janela, o sistema só permite template aprovado. Na F1 os templates são cadastrados e aprovados fora do sistema e apenas sincronizados ou referenciados; gerenciá-los aqui é WPP-10, da F2.

### 5.6 Notificações (NOT-01 a 03)

- **Eventos:**
  - novo lead de tráfego;
  - lead precisa de intervenção humana (na F1, só por marcação manual; na F2, também pela IA);
  - venda finalizada;
  - venda não concluída;
  - lead quente.
- **Conteúdo:** nome do lead, origem, resumo curto e link para a conversa.
- **Canais:** dentro do sistema e por WhatsApp para o número do gestor.
- **Envio ao gestor:** é iniciado pela empresa, então usa template aprovado e tem **custo por mensagem**, a entrar no preço do pacote.
- **Prazo:** até 10 segundos após o evento.

### 5.7 Lista de leads e exportação (LST-01, 02)

- **Filtros:** temperatura, origem, etapa, responsável, tags e período.
- **Formatos:** Excel, CSV e PDF, gerados no servidor.
- **Quem exporta:** só o gestor (`OWNER` e `MANAGER`).
- **Controle:** cada exportação é auditada desde a F1, por conter dados pessoais.
- **Arquivos:** ficam em armazenamento privado com validade curta.

### 5.8 Dashboard e relatórios (REL-01, 02)

- **Dashboard da recepção:** leads do dia, conversas em aberto, aguardando humano, vendas do dia e do mês. Na F1 é atualizado por consulta periódica; ao vivo é REL-03, da F2.
- **Relatório mensal:**
  - total de contatos e contatos por origem;
  - leads por temperatura e taxa de conversão;
  - vendas fechadas, perdidos por motivo e faturamento registrado;
  - leads frios revertidos em venda e procedimentos mais vendidos.
- **Definições:** fórmulas exatas (denominadores, período por data de criação ou de fechamento, o que conta como "frio revertido") a fixar em CRM-050, com exemplos numéricos antes da implementação.

## 6. Fase 2 e sugestões (não iniciar sem autorização)

- **IA de atendimento (IA-01 a 09):**
  - base de conhecimento por clínica, qualificação e temperatura automática;
  - transferência a humano com resumo (desenho prévio nas ADR-006 e ADR-007);
  - guardrails clínicos, log de respostas e identificação como assistente virtual;
  - camada isolada para trocar de provedor de modelo.
- **Automações (AUT-01 a 08):**
  - aniversário, ofertas por segmento só com opt-in, régua pós-procedimento e reativação aos 30, 60 e 90 dias;
  - limite de velocidade e janela de horário, com descadastro por "SAIR";
  - relatório de cada disparo.
- **Demais da Fase 2:** rodízio de leads, filtros salvos, dashboard ao vivo, indicadores de atendimento, PDF do relatório, painel consolidado da agência, módulos por clínica, auditoria completa, anonimização a pedido (PAC-11) e cobrança automatizada.
- **Sugestões:** agenda integrada e lembretes de consulta, exportação programada, resumo semanal ao dono, editor de regras e white-label da agência.

## 7. Modelo de dados

### Onde cada entidade vive (ADR-014)

| Banco | Entidades | Observação |
|---|---|---|
| **Central** | `tenants` (clínica, com `agency_id` opcional, plano e situação), `agencies`, `agency_memberships`, `user_accounts`, `tenant_memberships`, `tenant_provisioning_tasks`, canais do WhatsApp (`phone_number_id` → clínica), planos, auditoria da plataforma | Sem conteúdo de mensagens nem dados de pacientes |
| **Da clínica** | Contatos, consentimentos, tags, procedimentos, profissionais (sem login), funis e etapas, motivos de perda, leads e eventos (etapa, temperatura, responsável, origem), vendas, conversas, mensagens e referências de mídia, respostas rápidas, templates referenciados, tarefas, notificações, importações e exportações, auditoria de negócio | Criadas por `db/tenant-migration` com `GRANT` mínimo a `${runtimeRole}` |
| **Armazenamento de objetos** | Mídias do WhatsApp, arquivos de importação e exportações | Prefixo por clínica, acesso privado, links temporários |

### Regras de modelagem

- **Identificadores e datas:** IDs UUID. Datas em UTC, exibidas no fuso da clínica.
- **Clínica:** entidades da clínica não têm `clinic_id`, porque o isolamento é físico.
- **Dinheiro:** em centavos inteiros (`BIGINT`), nunca ponto flutuante.
- **Usuários:** referências a usuários usam o `subject_id` global, validado contra participação ativa.
- **Telefone:** em E.164, único por clínica.
- **Leads:** um lead aberto por contato (índice parcial).
- **Histórico:** etapa, temperatura, responsável e origem são eventos imutáveis com autor.
- **Remoção:** retenção, exclusão e anonimização são decididas antes de implementar remoção definitiva (CRM-073).

## 8. Stack e arquitetura

| Camada | Adotado | Referência |
|---|---|---|
| Frontend | Next.js 16, React 19, TypeScript, Tailwind e shadcn/ui; responsivo e instalável (PWA) | ADR-012 |
| Backend | Java 21, Spring Boot 4.1 e Spring MVC REST; monólito modular | ADR-001, ADR-017 |
| Banco | PostgreSQL 17 + pgvector; banco central e um banco por clínica; Flyway | ADR-014 |
| Autenticação | Sessão de servidor, cookie HttpOnly e CSRF; TOTP para administradores | ADR-015 |
| Processos | API, `migrate`, `provisioner` e um worker de mensagens na mesma imagem | ADR-016, ADR-018, ADR-027 |
| Fila | Persistente no PostgreSQL, com aviso imediato (`LISTEN/NOTIFY`) para cumprir os 5 e 10 segundos; sem Redis enquanto não houver necessidade medida | ADR-005, ADR-027 |
| WhatsApp | WhatsApp Business Platform oficial (Cloud API ou provedor parceiro, a decidir) | ADR-027 |
| E-mail | Resend | ADR-021 |
| Arquivos | Armazenamento de objetos compatível com S3, na mesma região | ADR-027 |
| Hospedagem | AWS Lightsail em São Paulo (`sa-east-1`) para o backend; Vercel para o Next.js | ADR-022 (revisada) |

- **Módulos existentes:** `identity`, `tenant`, `provisioning` e `config`.
- **Módulos planejados:** `agency`, `contacts`, `sales` (funil, leads e vendas), `messaging` (WhatsApp e caixa de entrada), `origin`, `tasks`, `notifications`, `exports`, `analytics`, `audit` e `platform`.
- **Na F2:** `ai` e `automations`.
- **Padrão:** camadas em [padroes-backend.md](padroes-backend.md).

**Rotas de rede:**
- **Navegador:** usa `app.<domínio>` com rewrite de `/api` para o backend, sem mudança de cookie.
- **Meta:** chama `api.<domínio>/api/v1/webhooks/whatsapp` direto no Lightsail. É a única rota pública sem sessão, protegida por assinatura.
- **Atualização da caixa de entrada:**
  - na F1, consulta periódica curta;
  - eventos do servidor (SSE) direto na API, se o rewrite da Vercel não sustentar conexões longas (decidir em CRM-080).

## 9. Requisitos não funcionais

| Área | Requisito do cliente | Como atendemos |
|---|---|---|
| LGPD | Dados de pacientes e procedimentos podem ser dados de saúde | Minimização, consentimento registrado, inventário e retenção em CRM-073 antes do piloto; contrato com clínica e agência definindo controlador e operadores |
| Segurança | HTTPS, criptografia em repouso, hash forte, 2FA, backup diário, logs de acesso, segredos fora do código | TLS no Nginx; disco e backups criptografados; senhas com hash forte; TOTP; backup diário fora da VM com restauração testada (CRM-072); tokens do WhatsApp no armazenamento de segredos |
| Isolamento | Separação por clínica em toda consulta | Banco e credencial por clínica e prova A/B em cada módulo |
| Desempenho | Lead em até 5 s; notificação em até 10 s | Webhook grava e responde; fila com `NOTIFY`; medido em teste de carga leve e no piloto |
| Disponibilidade | 99% ou mais; não perder mensagens | A Meta reenvia webhooks não confirmados; eventos gravados antes do 200; monitoramento e alerta. Uma VM é ponto único de falha (risco aceito no início, revisar com o crescimento) |
| Usabilidade | Português, simples, responsivo, PWA | Telas para celular primeiro na caixa de entrada; manifesto PWA (push só na F2) |
| Escala | Começar com 3 a 5 clínicas | Pools por clínica com limite (ADR-018); revisar ao passar de dezenas de clínicas |
| Observabilidade | Alertas de falha de webhook, conexão e envio | Logs sanitizados e alertas em CRM-070 |
| Hospedagem | Servidores no Brasil | Lightsail `sa-east-1` e armazenamento na mesma região; a Vercel serve só a interface |

## 10. Segurança e privacidade

- **Webhook:** é a nova superfície pública. Assinatura conferida em tempo constante, corpo bruto preservado para a verificação e limite de tamanho. Nunca confiar em `phone_number_id` sem o mapeamento central.
- **Tokens do WhatsApp:** ficam fora do código e dos logs; a API os lê, e só um fluxo dedicado os grava (ADR-027).
- **Saúde:** procedimentos e conversas podem revelar dados de saúde. Sem campos clínicos, sem fotos de antes e depois armazenadas por padrão, e com retenção definida para mídias.
- **Agências:** agem como operadoras e só acessam dados de pacientes com participação explícita e auditada.
- **Publicidade em saúde:** regras de conselhos (CFM, CRO) condicionam textos de disparos e da IA (F2). A validação é da clínica.
- **Opt-in:** obrigatório para ofertas (F2); opt-out sempre prevalece.
- **Pendências jurídicas:** listadas sem afirmar conformidade.

## 11. Critérios de aceite do MVP (seção 9 do documento)

Valem numa clínica real de teste:

- [ ] Um número novo envia mensagem pelo anúncio e, em até 5 segundos, aparece como lead na etapa Novo com origem Tráfego pago.
- [ ] A equipe recebe "Novo lead de tráfego" com link para a conversa.
- [ ] Um segundo contato do mesmo número não duplica o contato.
- [ ] Vários atendentes respondem pela caixa de entrada, com histórico completo.
- [ ] O lead é movido no funil, recebe temperatura e tarefa, e termina em venda ou perda com motivo.
- [ ] Ao registrar venda ou perda, o responsável recebe a notificação.
- [ ] A lista de leads filtra por temperatura e origem e exporta para Excel, CSV e PDF.
- [ ] O relatório mensal mostra contatos, vendas, conversão, frios revertidos e faturamento registrado.
- [ ] Uma clínica não vê dados de outra.

Nossos acréscimos:
- [ ] Uma agência não lê dados de pacientes de clínicas da carteira sem participação.
- [ ] Uma agência não alcança clínicas de outra agência.
- [ ] Papéis são aplicados no servidor.
- [ ] Exportações e ações críticas são auditadas.
- [ ] Um backup de teste é restaurado.
- [ ] Os fluxos funcionam no celular.

## 12. Roadmap

A ordem de execução está em [TASKS.md](../TASKS.md). Os riscos técnicos maiores vêm primeiro:

1. **Prova do WhatsApp:** Cloud API com número de teste e anúncio de teste, para confirmar o `referral` antes de escolher o provedor (CRM-005).
2. **Fundação:** papéis novos, agências, 2FA, convites e JPA de clínica.
3. **Dados comerciais:** contatos, funil, leads, vendas e origem.
4. **Atendimento:** WhatsApp de entrada, caixa de entrada, envio e notificações.
5. **Dados de saída:** lista, exportação, dashboard e relatório mensal.
6. **Lançamento:** operação em São Paulo, LGPD, testes de isolamento e piloto com a agência.

A estimativa anterior (12 a 16 semanas) não vale mais: o WhatsApp e a caixa de entrada aumentam o MVP. A reestimativa sai depois de CRM-005 e CRM-080.

## 13. Decisões em aberto

- [ ] **Fornecedor do WhatsApp:** Meta Cloud API direta (tornando-se Tech Provider para conectar números de clientes) ou provedor parceiro. Critérios: repasse do `referral`, custo, suporte e conexão de números das clínicas. Decidir depois de CRM-005.
- [ ] **Cobrança:** modelo para agências e clínicas (mensalidade, por clínica, por usuário ou por volume de mensagens), e se a cobrança fica dentro ou fora do sistema.
- [ ] **Agência suspensa:** o que acontece com as clínicas dela.
- [ ] **Agência e clínica:**
  - a agência recebe participação na clínica que cria, e com qual papel (proposta: `MANAGER`, revogável pelo `OWNER`);
  - como uma clínica sai de uma agência.
- [ ] **Notificação ao gestor por WhatsApp:** confirmar o custo por template e quem paga.
- [ ] **Ferramentas prontas:** usar algo pronto para a caixa de entrada ou para automações em vez de construir (seção 11 do documento).
- [ ] **Prazo:** prazo, orçamento e número de clínicas no lançamento.
- [ ] **Relatórios:** definições exatas das métricas (CRM-050).
- [ ] **Dados:** retenção de mensagens e mídias, exportação e exclusão; contratos de tratamento de dados (CRM-073).
- [-] **Nome do produto:** "Branly CRM" em uso; confirmação comercial pendente.
- [x] **Fonte do escopo:** documento do cliente v1.1 (ADR-024).
- [x] **Papéis:** Gestor, Atendente e Somente leitura (ADR-025).
- [x] **Agências:** como camada opcional sobre clínicas (ADR-026).
- [x] **Agenda própria:** fora do MVP (ADR-024).
- [x] **Região:** Lightsail São Paulo (ADR-022 revisada).

## 14. Instruções para agentes

As regras operacionais estão em [AGENTS.md](../AGENTS.md) e prevalecem sobre esta seção.

- Não implementar itens da F2 ou das sugestões sem pedido explícito.
- Toda tarefa cita os requisitos do cliente que atende (ex.: "Atende: WPP-02, RN-01").
- Inspecionar o repositório antes de presumir tabelas, endpoints ou bibliotecas.
- Webhook, agência, tokens do WhatsApp, exportação e dados de saúde sempre passam por `security`.
