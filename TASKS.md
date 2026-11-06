# TASKS.md — Backlog executável do MVP

> Fonte de requisitos: [`docs/planejamento-produto.md`](docs/planejamento-produto.md), baseado no [documento do cliente v1.1](docs/requisitos/requisitos-plataforma-clinicas-v1.1.pdf) (ADR-024). Regras dos agentes: [`AGENTS.md`](AGENTS.md).
> **Reorganizado em 06/11/2026** para o novo escopo: WhatsApp e origem do lead na Fase 1, agências parceiras e papéis do cliente. Os estados das tarefas já feitas foram mantidos como verificados em 05/11/2026. Marcação sem teste executado não prova funcionamento.
> **Estimativa:** a anterior (12–16 semanas) não vale mais; a reestimativa sai depois de CRM-005 e CRM-080.

## Como executar

- **Estados:**
  - `[ ]` pendente;
  - `[-]` em andamento ou parcial;
  - `[x]` concluído;
  - `[!]` bloqueado;
  - `[~]` retirado do MVP.
- **Prioridades:** **P0** fundamental ou segurança; **P1** essencial do MVP; **P2** melhoria antes ou depois do piloto.
- **Requisitos atendidos:** cada tarefa cita os IDs do cliente em **Atende**. Duas casas (`WPP-02`) são requisitos do cliente; três casas (`CRM-082`) são tarefas.
- Não começar uma tarefa antes de concluir as dependências. Uma tarefa por agente implementador. O coordenador é o único que edita este arquivo durante execução multiagente ([AGENTS.md §9](AGENTS.md)).
- **Registro de conclusão:**
  - PR ou commit;
  - arquivos alterados;
  - testes executados, com resultado;
  - observações e riscos.
- Checks canônicos e o que provam: [docs/qualidade.md](docs/qualidade.md).

## Ordem sugerida

1. **CRM-005:** prova do WhatsApp, que decide o fornecedor e é o maior risco.
2. **Fundação:** CRM-017 → CRM-018 → CRM-016 → CRM-013 → CRM-015.
3. **Dados comerciais:** CRM-020/021 → CRM-030/031/032/035 → CRM-090.
4. **Atendimento:** CRM-080 → CRM-081 → CRM-082/091 → CRM-083/084/085 → notificações.
5. **Dados de saída:** CRM-050 → CRM-051/053 → telas.
6. **Lançamento:** CRM-070–075 e piloto (CRM-074).

O frontend acompanha cada API aprovada.

## Fase 0 — Decisões e fundação

### [-] CRM-001 — Validar decisões arquiteturais (P0)
**Depende de:** nenhuma.
**Entregas:** ADRs da stack, monorepo, ORM, autenticação, deploy, isolamento e fuso horário.
**Aceite:** decisões aprovadas e registradas; dúvidas explicitadas; nenhuma tecnologia suposta como existente.
**Estado:**
- **Decididos:**
  - stack (ADR-001, ADR-012, ADR-017) e monorepo (ADR-011);
  - persistência (ADR-017, ADR-018), sessão (ADR-015), banco por clínica (ADR-014) e fuso;
  - escopo pelo documento do cliente (ADR-024), papéis (ADR-025) e agências (ADR-026);
  - e-mail com Resend (ADR-021) e hospedagem Lightsail São Paulo + Vercel (ADR-022 revisada).
- **Falta:**
  - fornecedor do WhatsApp (depois de CRM-005) e desenho do canal (CRM-080);
  - segredos na VM e proxy confiável (CRM-072).

### [x] CRM-002 — Criar estrutura do repositório e CI (P0)
**Depende de:** CRM-001.
**Entregas:** apps frontend/backend, configuração TypeScript estrita, lint, format, testes, scripts e `.env.example`.
**Aceite:** instalação, lint, typecheck, testes iniciais e build executam em ambiente limpo; CI replica as verificações.
**Estado (05/11/2026):** concluída em `feat/crm-004-api-conventions`.
- **Backend:** Spotless, ArchUnit, Surefire/Failsafe e Testcontainers.
- **Frontend:** TypeScript `strict`; `npm run lint` com zero avisos, `npm run typecheck` (`next typegen` + `tsc`) e build, todos executados localmente (o build nativo no Windows agora também passa).
- **CI:** `.github/workflows/ci.yml` roda `verify` e os checks do frontend em PR e push na `main`, com actions fixadas por SHA, token só de leitura e sem segredos; o wrapper do Maven confere o SHA-256 da distribuição.
- **Fora do escopo:** testes automatizados de frontend (ainda não há telas) e build das imagens Docker na CI.

### [-] CRM-003 — Configurar banco, migrations e ambientes (P0)
**Depende de:** CRM-001, CRM-002.
**Entregas:** PostgreSQL local/teste, ferramenta de migrations, política de backup/restauração e seeds sintéticos.
**Aceite:** banco pode ser criado do zero por migrations; restauração é testável; nenhum segredo versionado.
**Estado:**
- **Feito:**
  - PostgreSQL 17 + pgvector no Compose;
  - Flyway central V1–V6 e de clínica V1–V2, aplicadas do zero em `BootstrapIT` e `TenantIsolationIT`;
  - papéis separados ([credenciais-postgres.md](docs/credenciais-postgres.md)).
- **Falta:** política e teste de backup e restauração (central e por clínica) e seeds sintéticos locais.

### [x] CRM-004 — Definir contratos e convenções de API (P0)
**Depende de:** CRM-002.
**Entregas:** padrão REST, paginação, filtros, validação, erros, documentação OpenAPI e versionamento.
**Aceite:** endpoint de exemplo documentado e testado com respostas de sucesso/erro.
**Estado (05/11/2026):** concluída; contrato em [api-convencoes.md](docs/api-convencoes.md) (ADR-023).
- **Erros:** corpo `{code, message, fields}` em todas as respostas, inclusive filtros de segurança, rotas inexistentes e erros internos (`GlobalErrorHandler`).
- **Listas e validação:** paginação até a linha 10.000; filtros e validação em records de domínio, em vez de Bean Validation.
- **OpenAPI e versionamento:** erros comuns e esquema `ApiError` em toda operação; versionamento `/v1`.
- **Endpoint de exemplo:** `GET /v1/clinic/team`, paginado e com filtros `role` e `active`.
- **Testes:** `./mvnw -B -ntp clean verify` → 99 unitários (2 ignorados no Windows) e 31 de integração passaram, entre eles `ApiConventionsIT`, `PageQueryTest` e `TeamQueryTest`.
- **Revisões:** `security` e `reviewer`. Correção aplicada: o login não revela mais por tempo de resposta se um e-mail existe e está inativo.
- **Pendência:** a dependência de validação continua no `pom.xml` sem uso (ADR-023).

### [ ] CRM-005 — Prova técnica do WhatsApp oficial e da origem (P0)
**Depende de:** nenhuma (pode correr em paralelo à fundação).
**Atende:** risco de ORI-02, WPP-01, WPP-02; decisão do fornecedor (seção 11 do documento do cliente).
**Entregas:**
- App de teste na Meta com número de teste da Cloud API e webhook temporário recebendo eventos assinados.
- Anúncio "Clique para WhatsApp" de verba mínima, para capturar um `referral` real.
- Teste de link `wa.me` com código.
- Relatório com:
  - campos recebidos;
  - latências medidas;
  - limites encontrados (WhatsApp Web, coexistência com o app Business, como conectar números de clientes como Tech Provider);
  - comparação com um provedor parceiro candidato.
**Aceite:**
- Evidência (payload com dados pessoais mascarados) de que o `referral` chega.
- Recomendação de fornecedor documentada para decisão do responsável.
- Nenhum token ou número real versionado.
**Observação:** usa conta e anúncio reais da Meta; exige aprovação e credenciais fornecidas pelo responsável fora do chat.

## Fase 1 — Identidade, agências, clínicas e isolamento

### [x] CRM-010 — Modelar clínicas, usuários e vínculos (P0)
**Depende de:** CRM-003.
**Entregas:** tabelas de clínicas, usuários e vínculos, papéis/permissões e migrations.
**Aceite:** um usuário pode ter vínculo explícito com clínica; vínculos e status são validados por constraints e testes.
**Estado:** `tenants`, `user_accounts` e `tenant_memberships` (V2–V3) com constraints de nome derivado do UUID, imutabilidade do vínculo, estados válidos e versão de autenticação; testados em `SessionAuthenticationIT` e `TenantIsolationIT`.

### [-] CRM-011 — Implementar autenticação (P0)
**Depende de:** CRM-001, CRM-010.
**Atende:** ADM-03.
**Entregas:** login, logout, recuperação de senha por e-mail (Resend, ADR-021) e sessão segura.
**Aceite:** usuário não autenticado não acessa recursos protegidos; sessões inválidas são rejeitadas; recuperação usa token de uso único guardado como hash e não revela se o e-mail existe.
**Estado:**
- **Feito:**
  - login e logout por filtros do Spring Security;
  - CSRF, cookie HttpOnly/SameSite e revogação por `auth_version`;
  - limite de tentativas e respostas de login sem canal de tempo ([autenticacao.md](docs/autenticacao.md)).
- **Falta:**
  - recuperação de senha;
  - sessões compartilhadas para várias réplicas;
  - convite (CRM-013) e 2FA (CRM-016).

### [x] CRM-012 — Implementar contexto tenant e RBAC (P0)
**Depende de:** CRM-010, CRM-011.
**Entregas:** resolução de clínica ativa, guards/policies por papel e helpers de acesso com escopo.
**Aceite:** usuário da clínica A não acessa recursos da B, mesmo com IDs conhecidos ou parâmetros manipulados; testes de negação e permissão passam.
**Estado (05/11/2026):** concluída na branch `feat/crm-012-rbac`.
- Seleção de clínica revalidada a cada requisição e prova A/B física (ADR-014, ADR-018).
- Cinco papéis e matriz executável em `tenant/domain/ClinicRole` (ADR-020); `TenantAccessService.require` e `ClinicAccess` como helpers de escopo. **O catálogo muda em CRM-017.**
- V6: catálogo novo, `ADMIN`→`OWNER`, triggers de último proprietário e de ativação, runtime sem `DELETE` e com `UPDATE (role, active)` em participações.
- Gestão de equipe na clínica selecionada: `GET /v1/auth/clinic` e `GET`/`PUT`/`DELETE` em `/v1/clinic/team`.
- **Testes:** `./mvnw -B -ntp clean verify` → 44 unitários (2 ignorados no Windows) e 22 de integração passaram, entre eles `ClinicRoleTest`, `TeamPolicyTest`, `TeamManagementIT` e `TeamRuntimeGrantsIT`. No Compose, V6 aplicada e `verify-roles.sh` passou.
- **Revisões:** `architect` antes, `security` (achados baixos corrigidos) e `reviewer` (sugestões aplicadas).
- **Pendências levadas adiante:**
  - auditoria das ações de equipe (CRM-070);
  - convite e reativação de membros (CRM-013);
  - desativação de contas pela plataforma travando a clínica;
  - e-mail de ex-membros (CRM-073);
  - decidir antes da CRM-013 se a gestão de equipe fica toda no módulo `tenant` (hoje o controller está em `identity`).

### [ ] CRM-017 — Migrar para os papéis do cliente (P0)
**Depende de:** CRM-012.
**Atende:** ADM-02 (ADR-025).
**Entregas:**
- **Migração central V7:**
  - constraint com `OWNER`, `MANAGER`, `ATTENDANT` e `VIEWER`;
  - conversão `RECEPTION`/`SALES` → `ATTENDANT` e `PROFESSIONAL` → `VIEWER`;
  - triggers da V6 preservados.
- **Código:** `ClinicRole` e permissões conforme a matriz alvo de [permissoes.md](docs/permissoes.md); regras de equipe (`MANAGER` gerencia `ATTENDANT` e `VIEWER`).
- **Testes e documentação:** testes linha a linha da matriz; documentação atualizada.
**Aceite:**
- V7 aplica do zero e sobre a V6 com dados dos cinco papéis antigos.
- Matriz testada linha a linha.
- `TeamManagementIT` e `TeamRuntimeGrantsIT` passam com o catálogo novo.
- Nenhuma clínica ativa fica sem `OWNER`.

### [ ] CRM-018 — Agências parceiras e carteira de clínicas (P0)
**Depende de:** CRM-017, CRM-011.
**Atende:** perfil Admin da assessoria (seção 1.2 do documento do cliente), ADR-026.
**Entregas:**
- **Banco central:** `agencies`, `agency_memberships` e `tenants.agency_id` opcional.
- **Módulo `agency`:**
  - listar a carteira, com nome, situação, plano e estado do canal;
  - criar clínica na carteira pelo provisionador, com o primeiro gestor pendente de convite;
  - o envio do convite em si é entregue em CRM-013, que depende desta tarefa.
- **Plataforma:** criação de agências (rota restrita da plataforma ou comando operacional, até CRM-060).
- **Decisão prévia:** registrar a política de participação da agência na clínica criada.
**Aceite:**
- **Isolamento entre agências:** Admin da agência A não lista, cria, altera nem convida em clínicas da agência B, nem em clínicas diretas; inexistente e alheia respondem igual.
- **Dados de pacientes:** usuário só de agência não lê dados de nenhuma clínica.
- **Concorrência:** a criação de clínica é idempotente sob repetição e concorrência.
- **Revisões:** `architect` antes; `security` obrigatória.

### [ ] CRM-016 — Segundo fator (TOTP) para administradores (P0)
**Depende de:** CRM-011, CRM-018.
**Atende:** ADM-03.
**Entregas:**
- Cadastro de TOTP com segredo protegido e códigos de recuperação guardados como hash.
- Verificação no login, obrigatória para Super Admin, Admin da agência e `OWNER`, opcional para os demais.
- Limite de tentativas e revogação de sessões ao trocar o fator.
**Aceite:**
- Login de administrador sem o segundo fator não ganha sessão autenticada.
- Código repetido dentro da janela é recusado.
- Códigos de recuperação são de uso único.
- Revisão de `security`.

### [-] CRM-013 — Convites, onboarding e configurações da clínica (P1)
**Depende de:** CRM-012, CRM-018.
**Atende:** ADM-01, ADM-02; base para ADM-06 (F2).
**Entregas:**
- **Convite:** por e-mail com validade, uso único e token guardado como hash; aceite e reativação de membros.
- **Configurações da clínica:** nome e fuso.
- **Telas:** login, aceite de convite, seleção de clínica, carteira da agência e acompanhamento do provisionamento.
**Aceite:** clínica nova entra no sistema sem enxergar registros de outras; convite vencido, reutilizado ou de outra clínica é recusado; perfis têm acessos adequados.
**Estado:** o provisionador assíncrono cria banco, papéis e segredo, verifica o isolamento e ativa a clínica com o `OWNER` (ADR-018). **Falta** o restante.

### [ ] CRM-014 — Cadastro público de clínica direta (P2)
**Depende de:** CRM-013, CRM-016.
**Entregas:** pedido de cadastro idempotente, verificação de e-mail com token guardado só como hash, reenvio limitado e tarefa de provisionamento na mesma transação ([plano-onboarding.md](docs/plano-onboarding.md), etapas 2–3).
**Aceite:**
- Cadastro repetido ou concorrente não duplica identidade, clínica ou tarefa.
- Token vencido, reutilizado ou incorreto é recusado.
- Respostas não revelam se o e-mail existe.
- Não é possível tomar conta existente.
**Observação:** necessário só para abrir venda direta em autosserviço. No piloto, clínicas diretas são criadas pela plataforma.

### [ ] CRM-015 — Persistência JPA da clínica (P0)
**Depende de:** CRM-012.
**Entregas:** unidade de persistência da clínica sobre `TenantConnectionPools`, com tenant explícito (sem ThreadLocal), sem desligar a JPA central; remoção da regra ArchUnit que proíbe `@Entity` (ADR-018).
**Aceite:** entidade de clínica só lê e grava no banco da clínica autorizada; teste A/B com a primeira entidade; nenhuma entidade no EntityManager central.

## Fase 2 — Contatos e histórico

Todas as tabelas desta fase em diante vivem no banco da clínica (`db/tenant-migration`), com `GRANT` explícito a `${runtimeRole}` em cada migração.

### [ ] CRM-020 — Modelar contatos, tags e consentimento (P1)
**Depende de:** CRM-015, CRM-017.
**Atende:** PAC-01, PAC-03, PAC-06, PAC-08.
**Entregas:**
- Tabelas:
  - `contacts`, com nome, telefone E.164 único, e-mail, data de nascimento, observações e arquivamento;
  - `tags` e `contact_tags`;
  - `contact_consents`, histórico de opt-in e opt-out por canal com data e origem.
- Índices de busca.
**Aceite:**
- Telefone duplicado na mesma clínica é recusado, inclusive sob concorrência.
- Formatos diferentes do mesmo número são normalizados igual.
- Opt-out mais recente prevalece.

### [ ] CRM-021 — API de contatos (P1)
**Depende de:** CRM-020, CRM-004.
**Atende:** PAC-01, PAC-03, PAC-06, PAC-08.
**Entregas:** criar, editar, listar, detalhar, buscar, filtrar, paginar, arquivar, tags e consentimento; aviso de duplicidade antes de criar.
**Aceite:** permissões da matriz testadas; filtros e buscas não vazam entre clínicas (A/B); dados inválidos recusados com `INVALID_INPUT`.

### [ ] CRM-022 — Telas de contatos e perfil (P1)
**Depende de:** CRM-021.
**Entregas:** lista, busca, filtros, cadastro, edição, perfil com histórico e consentimento, e estados vazio, erro e carregamento.
**Aceite:** atendente conclui cadastro e encontra contato no celular e no desktop; acessível por teclado.

### [ ] CRM-023 — Linha do tempo do contato e do lead (P1)
**Depende de:** CRM-021, CRM-032.
**Atende:** PAC-05, CRM-09, RN-08.
**Entregas:** notas e timeline unificada (eventos do lead, vendas, tarefas e, depois de CRM-083, mensagens), com autor e horário.
**Aceite:** eventos aparecem em ordem estável, com autor; usuário de outra clínica não lê notas (A/B).

### [ ] CRM-024 — Importação de contatos por planilha (P1)
**Depende de:** CRM-021.
**Atende:** PAC-07.
**Entregas:**
- Upload de CSV ou XLSX com limites de tamanho e linhas.
- Validação por linha e relatório de erros.
- Prévia antes de gravar, sem sobrescrever existentes sem confirmação.
- Processamento em segundo plano e arquivo apagado ao final.
**Aceite:**
- **Robustez:** planilha com erros não grava parcialmente sem aviso; importação repetida não duplica; arquivo malicioso (fórmulas, tamanho, tipo falso) é recusado ou neutralizado.
- **Permissões:** só o gestor importa.
- **Dependências:** biblioteca nova de XLSX exige aprovação.

## Fase 3 — Funil, leads e vendas

### [ ] CRM-030 — Modelar funis e etapas configuráveis (P1)
**Depende de:** CRM-015.
**Atende:** CRM-01.
**Entregas:** `pipelines` e `pipeline_stages` com ordem e tipo (aberta, ganha, perdida); funil padrão (Novo, Em atendimento, Agendado, Compareceu, Venda fechada, Perdido) criado pela migração de clínica; `loss_reasons` configuráveis com lista inicial.
**Aceite:** etapas pertencem a um funil da mesma clínica; ordem consistente; todo funil tem exatamente uma etapa ganha e uma perdida; toda clínica nova recebe o funil padrão.

### [ ] CRM-031 — Modelar leads, temperatura e eventos (P1)
**Depende de:** CRM-020, CRM-030.
**Atende:** CRM-03, CRM-04, CRM-08, CRM-09, RN-01, RN-08.
**Entregas:**
- `leads` (oportunidades), com contato, etapa, responsável, temperatura, situação e datas.
- **Um lead aberto por contato**, por índice parcial.
- `lead_events`, imutáveis, para etapa, temperatura, responsável e encerramento, com autor.
**Aceite:**
- Segundo lead aberto para o mesmo contato é recusado, inclusive sob concorrência.
- Lead novo só depois do encerramento do anterior.
- Responsável validado contra participação ativa.

### [ ] CRM-032 — API de leads e transições (P1)
**Depende de:** CRM-031, CRM-004.
**Atende:** CRM-03, CRM-04, CRM-06, CRM-08, RN-08, RN-09.
**Entregas:** criar, listar, filtrar, mover etapa, temperatura, responsável, notas e perda com motivo obrigatório; publicação dos eventos para notificações.
**Aceite:**
- **Regras:** perda sem motivo é recusada; toda transição gera evento.
- **Permissões:** testadas por papel.
- **Isolamento:** A/B em leitura, filtros e alterações.
- **Concorrência:** movimentos concorrentes não perdem eventos.

### [ ] CRM-033 — Kanban e lista do funil (P1)
**Depende de:** CRM-032, CRM-022.
**Atende:** CRM-02, ORI-04.
**Entregas:** colunas, cartões com selo de origem e temperatura, arrastar, visão em lista, filtros e detalhe do lead.
**Aceite:** movimento persiste; falha do servidor desfaz o cartão com mensagem clara; funciona no celular.

### [ ] CRM-034 — Catálogo de procedimentos e profissionais (P1)
**Depende de:** CRM-015.
**Atende:** CRM-05, PAC-04.
**Entregas:** procedimentos (nome, valor de referência em centavos, categoria, intervalo de retorno opcional, ativo); profissionais da clínica sem login (nome, ativo). Sem informação clínica.
**Aceite:** exclusivos por clínica; inativos não aparecem para novas vendas, mas continuam nas antigas.

### [ ] CRM-035 — Registro de vendas (P1)
**Depende de:** CRM-032, CRM-034.
**Atende:** CRM-05, PAC-04, RN-03, RN-04, RN-09.
**Entregas:**
- `sales` com lead, contato, procedimento, valor (obrigatórios), data, profissional e forma de pagamento.
- Registrar a venda move o lead a Venda fechada, na mesma transação.
- Correção e cancelamento só pelo gestor, com evento.
- Contato com venda passa a ser exibido como paciente.
- Tela rápida de registro (poucos cliques).
**Aceite:**
- Venda sem valor ou procedimento é recusada.
- O valor é inteiro em centavos.
- A venda herda a origem do lead.
- Correções ficam no histórico.
- A/B testado.

## Fase 4 — WhatsApp e caixa de entrada

### [ ] CRM-080 — Desenho do canal WhatsApp e da fila de entrada (P0)
**Depende de:** CRM-005.
**Atende:** WPP-01, WPP-02, requisitos não funcionais de desempenho e disponibilidade (ADR-027).
**Entregas:** ADR detalhada e plano de implementação:
- tabela central de canais (`phone_number_id` → clínica);
- armazenamento e escrita dos tokens sem dar à API o gravador genérico de segredos;
- rota do webhook no Nginx e verificação de assinatura;
- eventos de entrada no banco da clínica com unicidade por `wamid`;
- worker e fila com `NOTIFY` sem dados pessoais;
- armazenamento de objetos;
- atualização da caixa de entrada (consulta periódica ou SSE);
- limites de custo por clínica.
**Aceite:** revisado por `architect` e `security`; reestimativa do MVP registrada neste arquivo.

### [ ] CRM-081 — Conexão do número da clínica (P0)
**Depende de:** CRM-080, CRM-018.
**Atende:** WPP-01.
**Entregas:** registro do canal pela agência ou pela plataforma, conforme o fornecedor; teste de conexão; estado do canal visível ao gestor e à agência.
**Aceite:**
- **Exclusividade:** um número pertence a uma só clínica (unicidade central).
- **Isolamento:** a agência só conecta números de clínicas da própria carteira.
- **Tokens:** nunca aparecem em respostas ou logs.
- **Revisão:** `security`.

### [ ] CRM-082 — Webhook de entrada e criação automática de contato e lead (P0)
**Depende de:** CRM-081, CRM-021, CRM-031.
**Atende:** WPP-02, PAC-02, CRM-03, RN-01.
**Entregas:**
- `POST /v1/webhooks/whatsapp` com assinatura, desafio de verificação e limite de tamanho.
- Gravação idempotente do evento.
- Worker que cria ou reaproveita o contato e abre lead conforme RN-01.
**Aceite:**
- **Assinatura:** requisição sem assinatura válida é recusada sem gravar nada.
- **Idempotência:** o mesmo `wamid` reenviado não duplica contato, lead nem mensagem.
- **Roteamento:** `phone_number_id` desconhecido é descartado com log sanitizado; evento da clínica A nunca grava na B.
- **Latência:** a mensagem vira lead em até 5 s em teste local medido.
- **Revisão:** `security` obrigatória.

### [ ] CRM-083 — Caixa de entrada compartilhada (P1)
**Depende de:** CRM-082.
**Atende:** WPP-03, PAC-05.
**Entregas:**
- Conversas e mensagens no banco da clínica, com situação (aguardando, em atendimento) e responsável.
- Lista e conversa com histórico, atualizada quase em tempo real.
- Marcação "precisa de humano".
**Aceite:**
- Dois atendentes veem a mesma conversa atualizada.
- Assumir a conversa é atômico.
- `VIEWER` lê sem responder.
- A/B testado.
- Funciona no celular.

### [ ] CRM-084 — Envio de mensagens e mídias (P1)
**Depende de:** CRM-083, CRM-080.
**Atende:** WPP-04.
**Entregas:**
- Envio de texto, imagem, áudio, PDF e documentos.
- Recebimento e armazenamento de mídias em objetos privados, com links temporários.
- Tratamento de falhas e de timeout como resultado incerto (ADR-005).
**Aceite:**
- Envio repetido por timeout não duplica sem reconciliação.
- Mídia de uma clínica não é acessível por link ou ID a outra.
- Tipos e tamanhos validados.

### [ ] CRM-085 — Janela de 24 horas e templates (P1)
**Depende de:** CRM-084.
**Atende:** WPP-06.
**Entregas:** cálculo da janela por conversa; fora dela, só envio de template aprovado (sincronizado ou referenciado do fornecedor) com variáveis.
**Aceite:** envio livre fora da janela é bloqueado no servidor; template inexistente ou não aprovado é recusado.

### [ ] CRM-086 — Respostas rápidas (P1)
**Depende de:** CRM-083.
**Atende:** WPP-05.
**Entregas:** cadastro por clínica (gestor) e uso por atalho na conversa.
**Aceite:** respostas são exclusivas da clínica; só o gestor edita.

## Fase 5 — Origem, tarefas e notificações

### [ ] CRM-090 — Modelo de origem do lead (P0)
**Depende de:** CRM-031.
**Atende:** ORI-01, ORI-04, RN-02, RN-03.
**Entregas:** origem no lead (tipo, anúncio ou código, título, `ctwa_clid`); `lead_origin_events` para contatos seguintes; correção manual com evento.
**Aceite:** a origem inicial não é sobrescrita automaticamente; a correção manual fica na linha do tempo; a venda mantém a origem do lead.

### [ ] CRM-091 — Detecção automática da origem (P0)
**Depende de:** CRM-082, CRM-090.
**Atende:** ORI-02, ORI-03.
**Entregas:** classificação no worker:
- `referral` → Tráfego pago;
- código `[CODIGO]` → origem do código;
- senão → Não identificada.
**Aceite:**
- **Testes com payloads sintéticos:** anúncio, post impulsionado, código válido, código inválido e sem nada.
- **Retorno do contato:** contato antigo voltando por anúncio gera evento sem trocar a origem do lead aberto.
- **Prova real:** confirmada no piloto (CRM-074).

### [ ] CRM-092 — Selo, filtros e relatório por origem (P1)
**Depende de:** CRM-091, CRM-033.
**Atende:** ORI-04, ORI-05.
**Entregas:** selo "Tráfego" na lista, no Kanban e na conversa; filtros por origem e campanha; contagem de leads e vendas por origem.
**Aceite:** só contagens, sem custos; filtros conferem com fixtures.

### [ ] CRM-040 — Tarefas e follow-ups (P1)
**Depende de:** CRM-021, CRM-032.
**Atende:** CRM-07.
**Entregas:** tabela `tasks`, API e telas por responsável, prazo e conclusão, vinculadas a contato e lead.
**Aceite:** tarefa vincula registros da mesma clínica; filtros de hoje, atrasadas e futuras no fuso da clínica; permissões testadas.

### [ ] CRM-093 — Notificações dentro do sistema (P1)
**Depende de:** CRM-032, CRM-035, CRM-091.
**Atende:** NOT-01, NOT-02, RN-09.
**Entregas:**
- **Eventos:**
  - novo lead de tráfego;
  - precisa de humano;
  - venda finalizada;
  - venda não concluída;
  - lead quente.
- Destinatários: responsável e gestores.
- Conteúdo com nome, origem, resumo e link; marcação de lida.
**Aceite:** notificação gerada em até 10 s do evento; cada usuário só vê as próprias; nenhuma notificação vai a usuário de outra clínica ou sem participação ativa.

### [ ] CRM-094 — Notificação por WhatsApp ao gestor (P1)
**Depende de:** CRM-093, CRM-085.
**Atende:** NOT-03.
**Entregas:** número de notificação do gestor (com consentimento) e envio por template aprovado, com limite de envio e registro de custo estimado.
**Aceite:** sem template aprovado, não envia e registra o motivo; falha no WhatsApp não bloqueia a notificação interna.

## Fase 6 — Lista, dashboard e relatórios

### [ ] CRM-050 — Definir métricas (P0)
**Depende de:** CRM-032, CRM-035, CRM-090.
**Atende:** REL-01, REL-02.
**Entregas:** especificação com fórmula e período de cada indicador:
- conversão;
- leads por temperatura;
- frios revertidos (lead que esteve frio e terminou em venda);
- faturamento registrado;
- perdidos por motivo;
- procedimentos mais vendidos;
- contatos por origem.
**Aceite:** exemplos numéricos revisados pelo responsável; valor de venda registrado nunca é chamado de receita recebida.

### [ ] CRM-051 — API do dashboard da recepção e do relatório mensal (P1)
**Depende de:** CRM-050, CRM-083.
**Atende:** REL-01, REL-02.
**Entregas:** consultas agregadas por período no fuso da clínica.
**Aceite:** valores conferem com fixtures conhecidas; nenhuma agregação inclui outra clínica; permissões testadas.

### [ ] CRM-052 — Telas de dashboard e relatório (P1)
**Depende de:** CRM-051.
**Entregas:** cards do dashboard com atualização periódica, relatório mensal com gráficos e tabelas, e estados vazios.
**Aceite:** números iguais aos da API; responsivo.

### [ ] CRM-053 — Lista de leads e exportação (P1)
**Depende de:** CRM-032, CRM-092.
**Atende:** LST-01, LST-02.
**Entregas:**
- Lista filtrável por temperatura, origem, etapa, responsável, tags e período.
- Exportação para Excel, CSV e PDF no servidor, com arquivo privado e validade curta.
**Aceite:**
- **Permissões:** só o gestor exporta.
- **Conteúdo:** a exportação respeita os filtros e só contém a clínica selecionada.
- **CSV:** neutraliza fórmulas.
- **Auditoria:** cada exportação é registrada.
- **Dependências:** bibliotecas de XLSX e PDF exigem aprovação.

## Fase 7 — Administração da plataforma

### [ ] CRM-060 — Gestão interna de agências, clínicas e planos (P1)
**Depende de:** CRM-018, CRM-016.
**Entregas:** `plans` e assinaturas no banco central; painel da plataforma para agências e clínicas diretas (criar, plano, suspender, reativar), com alteração auditada.
**Aceite:** acesso só do Super Admin, com 2FA; dados das clínicas não ficam expostos ao suporte por padrão.

### [ ] CRM-061 — Situação da assinatura e limites (P1)
**Depende de:** CRM-060.
**Entregas:** regras de plano ativo, suspensão de clínica e de agência, e limites aprovados (inclusive de mensagens).
**Aceite:** regras documentadas e testadas; nenhuma cobrança automática sem decisão.

## Fase 8 — Qualidade, operação e piloto

### [ ] CRM-070 — Auditoria e observabilidade (P0)
**Depende de:** CRM-012, CRM-032, CRM-082.
**Entregas:**
- **Auditoria mínima da F1:** equipe, papéis, agência, exportações, vendas corrigidas e canal.
- **Logs:** sanitizados.
- **Alertas:** falha de webhook, de envio e de conexão do número.
- **Medição:** latência de entrada e de notificação.
**Aceite:** ações relevantes rastreáveis sem segredos nem dados pessoais excessivos; alerta disparado em teste de falha.
**Observação:** a auditoria completa é ADM-04, da F2.

### [ ] CRM-071 — Testes de segurança e isolamento (P0)
**Depende de:** CRM-053, CRM-060, CRM-070, CRM-084.
**Entregas:** suíte entre clínicas e entre agências, RBAC, webhook (assinatura, reenvio, roteamento), mídias, exportações e operações administrativas.
**Aceite:** tentativas de ler, listar, editar, exportar ou agregar dados de outra clínica falham em todos os módulos.
**Base existente:** `TenantIsolationIT`, `SessionAuthenticationIT`, `TeamManagementIT`.

### [ ] CRM-072 — Deploy, backup e operação (P0)
**Depende de:** CRM-003, CRM-070, CRM-080.
**Entregas:** deploy na VM Lightsail em São Paulo e na Vercel (ADR-022 revisada):
- **Rede:** rewrite `/api`; rota pública do webhook em `api.<domínio>`; TLS e firewall.
- **Segurança:** proxy confiável para o IP real; variáveis e segredos.
- **Arquivos:** armazenamento de objetos.
- **Backup:** diário, criptografado e fora da VM; procedimento de restauração (central e por clínica).
**Aceite:** restauração realizada em ambiente isolado; monitoramento e recuperação documentados.

### [ ] CRM-073 — Revisão de privacidade e LGPD (P0)
**Depende de:** CRM-053, CRM-083.
**Entregas:**
- **Inventário de dados:** saúde, mensagens e mídias; papéis de controlador e operador para clínica, agência e plataforma.
- **Regras de dados:** retenção, exportação, exclusão e anonimização (antecipa decisões de PAC-11).
- **Comunicação:** consentimento e política de privacidade; pendências jurídicas.
**Aceite:** riscos tratados ou formalmente bloqueados antes do piloto; não declarar conformidade jurídica sem avaliação adequada.

### [ ] CRM-075 — PWA e usabilidade no celular (P2)
**Depende de:** CRM-083.
**Entregas:** manifesto e instalação, layout de celular na caixa de entrada, Kanban e registro de venda. Notificação push é F2.
**Aceite:** jornadas principais executadas num celular real; acessibilidade básica verificada.

### [ ] CRM-042 — Jornada integrada do anúncio à venda (P1)
**Depende de:** CRM-035, CRM-040, CRM-085, CRM-091, CRM-093.
**Entregas:** teste de ponta a ponta com payloads sintéticos:
1. mensagem com `referral`;
2. lead com origem;
3. notificação;
4. resposta pela caixa de entrada;
5. temperatura e tarefa;
6. venda;
7. notificação final;
8. lista e relatório.
**Aceite:** os critérios da seção 11 do planejamento passam em ambiente local, salvo os que exigem anúncio real.

### [ ] CRM-074 — Piloto com a agência e clínica de teste (P1)
**Depende de:** CRM-042, CRM-052, CRM-061, CRM-071, CRM-072, CRM-073.
**Entregas:** onboarding da agência e de uma clínica real de teste, anúncio real "Clique para WhatsApp", roteiro de feedback e registro de bugs.
**Aceite:** todos os critérios de aceite do MVP (seção 9 do documento do cliente) demonstrados em uso real autorizado.

## Retirado do MVP

- [~] CRM-041 — Agenda comercial e disponibilidade. Saiu pela ADR-024; o funil registra Agendado e Compareceu. Volta como sugestão SUG-001.

## Fase 2 — não iniciar sem autorização

- [ ] FUT-001 — IA de atendimento: base de conhecimento, qualificação, temperatura automática, transferência com resumo, guardrails e log (IA-01 a 09, CRM-11, WPP-08, RN-06, RN-07). Desenho prévio: ADR-006, ADR-007.
- [ ] FUT-002 — Automações e disparos com opt-in e descadastro por "SAIR" (AUT-01 a 08, PAC-09, PAC-10, RN-05).
- [ ] FUT-003 — Canal avançado: status de entrega e leitura, monitoramento da conexão, gestão de templates (WPP-07, 09, 10).
- [ ] FUT-004 — Notificações avançadas: preferências por usuário, alerta de lead sem resposta, push e e-mail (NOT-04 a 06).
- [ ] FUT-005 — Relatórios avançados: dashboard ao vivo, comparativo mensal, indicadores de atendimento, PDF com logo, painel consolidado da agência (REL-03 a 07).
- [ ] FUT-006 — Operação: auditoria completa, módulos por clínica, assistente de onboarding (ADM-04 a 06), rodízio de leads (CRM-10), filtros salvos (LST-03), cadastro de campanhas (ORI-06).
- [ ] FUT-007 — Exclusão e anonimização a pedido (PAC-11); as regras são decididas antes, em CRM-073.
- [ ] FUT-008 — Cobrança recorrente automatizada.

## Sugestões do cliente — só com pedido explícito

- [ ] SUG-001 — Agenda integrada e lembrete de consulta (AUT-09). Desenho prévio: ADR-004.
- [ ] SUG-002 — Exportação programada por e-mail (LST-04).
- [ ] SUG-003 — Resumo semanal ao dono por WhatsApp (REL-08).
- [ ] SUG-004 — Editor de regras "quando… então…" (AUT-10).
- [ ] SUG-005 — White-label da agência (ADM-07).
- [ ] SUG-006 — Ideias da seção 12 do documento: NPS, indicação, previsão de retorno, score de lead e biblioteca de templates.

## Decisões pendentes para o responsável pelo produto

- [ ] Fornecedor do WhatsApp (depois de CRM-005).
- [ ] Cobrança de agências e clínicas: modelo, preços, limites e se fica dentro ou fora do sistema.
- [ ] Participação da agência nas clínicas que cria; suspensão de agência; saída de clínica da agência.
- [ ] Custo das notificações ao gestor por WhatsApp.
- [ ] Ferramentas prontas para caixa de entrada ou automações.
- [ ] Prazo, orçamento e número de clínicas no lançamento.
- [ ] Definições das métricas (CRM-050).
- [ ] Retenção de mensagens e mídias; contratos de tratamento de dados (CRM-073).
- [-] Nome e identidade do SaaS ("Branly CRM" em uso).
- [x] Escopo pelo documento do cliente (ADR-024); papéis (ADR-025); agências (ADR-026); agenda fora do MVP; região São Paulo (ADR-022).

## Template de execução por tarefa

```md
ID: CRM-XXX
Atende: (IDs do documento do cliente)
Status: [ ] → [-] → [x]
Responsável/agente:
Dependências verificadas:
Plano aprovado:
Arquivos alterados:
Critérios de aceite validados:
Testes executados e resultados:
Riscos / bloqueios:
PR ou commit (se houver):
```

## Primeira instrução para um agente

> Leia `AGENTS.md`, `docs/planejamento-produto.md` e `TASKS.md`. Inspecione o repositório e confirme o estado das tarefas `[-]`. Proponha a próxima tarefa desbloqueada (hoje: CRM-005 em paralelo com CRM-017), com plano, arquivos, riscos, testes e papéis de agente. Aguarde aprovação antes de implementar.
