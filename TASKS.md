# TASKS.md — Backlog executável do MVP

> Fonte de requisitos: [`docs/planejamento-crm-clinicas-estetica.md`](docs/planejamento-crm-clinicas-estetica.md). Regras dos agentes: [`AGENTS.md`](AGENTS.md).
> **Estado verificado em 05/11/2026** contra o repositório (PRs #1–#3 mergeados na `main`). Marcação sem teste executado não prova funcionamento. Estimativa macro: 12–16 semanas para uma equipe pequena e experiente, sujeita a validação.

## Como executar

- Estados: `[ ]` pendente; `[-]` em andamento ou parcial; `[x]` concluído; `[!]` bloqueado.
- Prioridades: **P0** fundamental/segurança; **P1** essencial do MVP; **P2** melhoria antes ou depois do piloto.
- Cada tarefa tem ID estável, dependências e aceite verificável. Não começar uma tarefa antes de concluir as dependências.
- Uma tarefa por agente implementador. O coordenador é o único que edita este arquivo durante execução multiagente ([AGENTS.md §9](AGENTS.md)).
- Registrar em cada conclusão: PR/commit, arquivos alterados, testes executados com resultado, observações e riscos.
- Checks canônicos e o que provam: [docs/qualidade.md](docs/qualidade.md).

## Fase 0 — Decisões e fundação

### [-] CRM-001 — Validar decisões arquiteturais (P0)
**Depende de:** nenhuma.
**Entregas:** ADR com stack final, monorepo ou repositórios separados, ORM, provedor de autenticação, deploy, estratégia de isolamento e fuso horário.
**Aceite:** decisões aprovadas e registradas; dúvidas explicitadas; nenhuma tecnologia suposta como existente.
**Estado:** decididos stack (ADR-001, ADR-012, ADR-017), monorepo (ADR-011), persistência JDBC em identidade/registro e JPA de clínica adiada (ADR-017, ADR-018), autenticação por sessão (ADR-015), banco por clínica (ADR-014), UTC no banco e fuso da clínica (ADR-004) e escopo do MVP (ADR-019). Papéis (ADR-020), e-mail com Resend (ADR-021) e hospedagem (ADR-022: Lightsail para o backend e Vercel para o Next.js) decididos. **Falta:** armazenamento de segredos na VM (permissões, backup e rotação) e a configuração de proxy confiável descrita na ADR-022, ambos em CRM-072.

### [-] CRM-002 — Criar estrutura do repositório e CI (P0)
**Depende de:** CRM-001.
**Entregas:** apps frontend/backend, configuração TypeScript estrita, lint, format, testes, scripts e `.env.example`.
**Aceite:** instalação, lint, typecheck, testes iniciais e build executam em ambiente limpo; CI replica as verificações.
**Estado:** `backend/` com Spotless, ArchUnit, Surefire/Failsafe e Testcontainers; `frontend/` com TypeScript `strict`, ESLint e build; `.env.example`; Compose. **Falta:** CI (não existe `.github/workflows`), script `typecheck` e testes no frontend.

### [-] CRM-003 — Configurar banco, migrations e ambientes (P0)
**Depende de:** CRM-001, CRM-002.
**Entregas:** PostgreSQL local/teste, ferramenta de migrations, política de backup/restauração e seeds sintéticos.
**Aceite:** banco pode ser criado do zero por migrations; restauração é testável; nenhum segredo versionado.
**Estado:** PostgreSQL 17 + pgvector no Compose; Flyway central V1–V5 e de clínica V1–V2, aplicadas do zero em `BootstrapIT` e `TenantIsolationIT`; papéis separados ([credenciais-postgres.md](docs/credenciais-postgres.md)). **Falta:** política e teste de backup/restauração (banco central e por clínica) e seeds sintéticos locais.

### [-] CRM-004 — Definir contratos e convenções de API (P0)
**Depende de:** CRM-002.
**Entregas:** padrão REST, paginação, filtros, validação, erros, documentação OpenAPI e versionamento.
**Aceite:** endpoint de exemplo documentado e testado com respostas de sucesso/erro.
**Estado:** prefixo `/v1` sob o contexto `/api`, OpenAPI nos controllers e nas rotas de filtro, erros `{"message"}` por `ErrorHandler` de módulo, endpoints de sessão testados em `SessionAuthenticationIT`. **Falta:** convenção de paginação/filtros e de validação de entrada (Bean Validation) e o código estável de erro consumível pelo frontend.

## Fase 1 — Identidade, clínicas e isolamento

### [x] CRM-010 — Modelar clínicas, usuários e vínculos (P0)
**Depende de:** CRM-003.
**Entregas:** tabelas de clínicas, usuários e vínculos, papéis/permissões e migrations.
**Aceite:** um usuário pode ter vínculo explícito com clínica; vínculos e status são validados por constraints e testes.
**Estado:** `tenants`, `user_accounts` e `tenant_memberships` (V2–V3) com constraints de nome derivado do UUID, imutabilidade do vínculo, estados válidos e versão de autenticação; testados em `SessionAuthenticationIT` e `TenantIsolationIT`. O catálogo de papéis é revisto em CRM-012.

### [-] CRM-011 — Implementar autenticação (P0)
**Depende de:** CRM-001, CRM-010.
**Entregas:** login, logout, recuperação de acesso, sessão segura e fluxo de convite conforme o provedor aprovado.
**Aceite:** usuário não autenticado não acessa recursos protegidos; sessões inválidas são rejeitadas; testes passam.
**Estado:** login/logout por filtros do Spring Security, CSRF, cookie HttpOnly/SameSite, revogação por `auth_version`, limite de tentativas ([autenticacao.md](docs/autenticacao.md)). **Falta:** recuperação de senha, convite, segundo fator para administradores, sessões compartilhadas para múltiplas réplicas.

### [x] CRM-012 — Implementar contexto tenant e RBAC (P0)
**Depende de:** CRM-010, CRM-011.
**Entregas:** resolução de clínica ativa, guards/policies por papel e helpers de acesso com escopo.
**Aceite:** usuário da clínica A não acessa recursos da B, mesmo com IDs conhecidos ou parâmetros manipulados; testes de negação e permissão passam.
**Estado (05/11/2026):** concluída na branch `feat/crm-012-rbac`.
- Seleção de clínica revalidada a cada requisição e prova A/B física (ADR-014, ADR-018).
- Cinco papéis e matriz executável em `tenant/domain/ClinicRole` (ADR-020, [permissoes.md](docs/permissoes.md)); `TenantAccessService.require` e `ClinicAccess` como helpers de escopo.
- V6: catálogo novo, `ADMIN`→`OWNER`, triggers de último proprietário e de ativação, runtime sem `DELETE` e com `UPDATE (role, active)` em participações.
- Gestão de equipe na clínica selecionada: `GET /v1/auth/clinic` e `GET`/`PUT`/`DELETE` em `/v1/clinic/team`.
- **Testes:** `./mvnw -B -ntp clean verify` → 44 unitários (2 ignorados no Windows) e 22 de integração passaram, entre eles `ClinicRoleTest`, `TeamPolicyTest`, `TeamManagementIT` (A/B, CSRF, papéis, último proprietário, concorrência e revogação) e `TeamRuntimeGrantsIT`. No Compose, V6 aplicada e `verify-roles.sh` passou.
- **Revisões:** `architect` antes, `security` (achados baixos corrigidos) e `reviewer` (sugestões aplicadas).
- **Pendências levadas adiante:**
  - cálculo de "próprios" em cada módulo de negócio;
  - auditoria das ações de equipe (CRM-070);
  - convite e reativação de membros (CRM-013);
  - desativação de contas pela plataforma travando a clínica (antes de existir esse fluxo);
  - e-mail de ex-membros (CRM-073);
  - decidir antes da CRM-013 se a gestão de equipe fica toda no módulo `tenant` (hoje o controller está em `identity`).

### [-] CRM-013 — Onboarding e configurações básicas da clínica (P1)
**Depende de:** CRM-012, CRM-014.
**Entregas:** criação/configuração inicial, nome, fuso horário e convite de equipe; telas de login, cadastro e acompanhamento do provisionamento.
**Aceite:** clínica nova entra no sistema sem enxergar registros de outras clínicas; perfis têm acessos adequados.
**Estado:** provisionador assíncrono cria banco, papéis e segredo, verifica o isolamento e ativa a clínica com o ADMIN (ADR-018). **Falta:** fuso e configurações da clínica, convite de equipe e telas no frontend.

### [ ] CRM-014 — Cadastro público e verificação de e-mail (P0)
**Depende de:** CRM-011.
**Entregas:** pedido de cadastro idempotente, token de verificação aleatório guardado só como hash, reenvio limitado, criação da tarefa de provisionamento na mesma transação ([plano-onboarding.md](docs/plano-onboarding.md), etapas 2–3). Envio pelo Resend com adaptador local em desenvolvimento (ADR-021).
**Aceite:** cadastro repetido ou concorrente não duplica identidade, clínica ou tarefa; token vencido, reutilizado ou incorreto é recusado; respostas não revelam se o e-mail existe; não é possível tomar conta existente informando seu e-mail.

### [ ] CRM-015 — Persistência JPA da clínica (P0)
**Depende de:** CRM-012.
**Entregas:** unidade de persistência da clínica sobre `TenantConnectionPools`, com tenant explícito (sem ThreadLocal), sem desligar a JPA central; remoção da regra ArchUnit que proíbe `@Entity` (ADR-018).
**Aceite:** entidade de clínica só lê/grava no banco da clínica autorizada; teste A/B com a primeira entidade; nenhuma entidade no EntityManager central.

## Fase 2 — Contatos e histórico

Todas as tabelas desta fase em diante vivem no banco da clínica (`db/tenant-migration`), com `GRANT` explícito a `${runtimeRole}` em cada migração.

### [ ] CRM-020 — Modelar contatos, tags e origem (P1)
**Depende de:** CRM-012, CRM-015.
**Entregas:** tabelas `contacts`, `tags`, `contact_tags`; índices por telefone normalizado, origem e busca.
**Aceite:** contato pertence à clínica; possíveis duplicatas são identificáveis dentro da mesma clínica; validações funcionam.

### [ ] CRM-021 — API CRUD de contatos (P1)
**Depende de:** CRM-020, CRM-004.
**Entregas:** criar, editar, listar, detalhar, filtrar, paginar e política segura de exclusão/arquivamento.
**Aceite:** CRUD testado; filtros e buscas não vazam registros entre clínicas; dados inválidos são rejeitados.

### [ ] CRM-022 — Telas de contatos e perfil (P1)
**Depende de:** CRM-021.
**Entregas:** lista, busca, filtros, cadastro, edição, perfil e estados vazio/erro/carregamento.
**Aceite:** recepcionista autorizada conclui cadastro e encontra contato; interface responsiva e acessível.

### [ ] CRM-023 — Atividades e histórico comercial (P1)
**Depende de:** CRM-021.
**Entregas:** `activities`, registro de ligações/notas/contatos e timeline por contato.
**Aceite:** eventos vinculados à clínica e ao contato corretos; usuário não autorizado não lê notas de outra clínica.

## Fase 3 — Funil e oportunidades

### [ ] CRM-030 — Modelar funis e etapas configuráveis (P1)
**Depende de:** CRM-012, CRM-015.
**Entregas:** `pipelines`, `pipeline_stages`, ordenação, etapa padrão e migrations.
**Aceite:** etapas pertencem a um funil da mesma clínica; ordenação consistente; funil padrão criado pela migração de clínica para todo tenant novo.

### [ ] CRM-031 — Modelar oportunidades e eventos (P1)
**Depende de:** CRM-020, CRM-030.
**Entregas:** `opportunities`, `opportunity_events`, responsável, procedimento de interesse, valor estimado, status e motivo de perda.
**Aceite:** um contato possui múltiplas oportunidades; FKs impedem relações inconsistentes; responsável validado contra participação ativa; valores monetários precisos.

### [ ] CRM-032 — API de oportunidades e transições (P1)
**Depende de:** CRM-031, CRM-004.
**Entregas:** CRUD, mudança de etapa, atribuição, fechamento ganho/perdido e histórico de eventos.
**Aceite:** perda exige motivo; ganho registra valor/data; transições geram eventos; autorização e isolamento testados.

### [ ] CRM-033 — Interface Kanban do funil (P1)
**Depende de:** CRM-032, CRM-022.
**Entregas:** colunas, cartões, filtros, mudança de etapa, detalhes e feedback de erro.
**Aceite:** usuário autorizado movimenta oportunidade; alteração persiste; falha do servidor não deixa estado enganoso.

### [ ] CRM-034 — Catálogo comercial de procedimentos (P1)
**Depende de:** CRM-012, CRM-031.
**Entregas:** cadastro simples de procedimentos de interesse, ativos/inativos, sem informações clínicas.
**Aceite:** procedimentos são exclusivos por clínica; oportunidades podem referenciá-los sem vazamento entre clínicas.

## Fase 4 — Tarefas e agenda comercial

### [ ] CRM-040 — Tarefas e follow-ups (P1)
**Depende de:** CRM-021, CRM-032.
**Entregas:** tabela `tasks`, API e telas por responsável, prazo, prioridade e conclusão.
**Aceite:** tarefa vincula contato/oportunidade da mesma clínica; filtros hoje/atrasadas/futuras funcionam no fuso da clínica; RBAC testado.

### [ ] CRM-041 — Agenda comercial e disponibilidade (P1)
**Depende de:** CRM-021, CRM-032, CRM-012.
**Entregas:** `appointments`, API e calendário com agendamento, confirmação, reagendamento, comparecimento, falta e cancelamento.
**Aceite:** conflito de horário do mesmo profissional bloqueado inclusive sob concorrência (ADR-004); horários respeitam o fuso da clínica; acesso entre clínicas negado.

### [ ] CRM-042 — Jornada integrada lead → venda (P1)
**Depende de:** CRM-023, CRM-033, CRM-040, CRM-041.
**Entregas:** navegação e vínculos entre contato, oportunidade, tarefa e avaliação.
**Aceite:** teste E2E cria lead, agenda avaliação, registra comparecimento, follow-up e fecha oportunidade sem inconsistências.

## Fase 5 — Indicadores e relatórios

### [ ] CRM-050 — Definir métricas e regras de atribuição (P0)
**Depende de:** CRM-032, CRM-041.
**Entregas:** especificação de denominadores, períodos, origem, responsável, conversão, comparecimento e receita comercial.
**Aceite:** exemplos numéricos revisados; métricas não confundem valor previsto com valor fechado nem coortes distintas.

### [ ] CRM-051 — API de dashboard e relatórios (P1)
**Depende de:** CRM-050.
**Entregas:** consultas agregadas por período, origem, vendedor, etapa, procedimento e motivos de perda.
**Aceite:** valores conferem com fixtures conhecidas; nenhuma agregação inclui outra clínica; filtros testados.

### [ ] CRM-052 — Telas de dashboard e relatórios (P1)
**Depende de:** CRM-051.
**Entregas:** cards de KPIs, gráficos, funil resumido, tabelas e filtros.
**Aceite:** números e estados vazios consistentes com a API; gestor vê dados permitidos; responsividade validada.

## Fase 6 — Administração SaaS mínima

### [ ] CRM-060 — Gestão interna de clínicas e planos (P1)
**Depende de:** CRM-012, CRM-013.
**Entregas:** `plans`, `subscriptions` (banco central) e painel interno para visualizar clínicas, plano e status, com alteração auditada.
**Aceite:** acesso restrito ao super admin com autorização própria; alterações críticas auditadas; dados comerciais das clínicas não ficam expostos ao suporte por padrão.

### [ ] CRM-061 — Estados de assinatura e limites básicos (P1)
**Depende de:** CRM-060.
**Entregas:** regras de plano ativo, suspensão e limites aprovados; tratamento de acesso expirado (status `SUSPENDED` já bloqueia conexões).
**Aceite:** regras documentadas e testadas; nenhuma cobrança automática sem decisão específica.

## Fase 7 — Qualidade, piloto e lançamento

### [ ] CRM-070 — Auditoria e observabilidade (P0)
**Depende de:** CRM-012, CRM-032.
**Entregas:** auditoria da plataforma no banco central e de negócio no banco da clínica, logs sanitizados, monitoramento de erros e eventos críticos.
**Aceite:** mudanças relevantes rastreáveis sem expor segredos ou dados pessoais excessivos.

### [ ] CRM-071 — Testes de segurança e isolamento (P0)
**Depende de:** CRM-042, CRM-051, CRM-060, CRM-070.
**Entregas:** suíte entre clínicas, RBAC, validação, autenticação e operações administrativas.
**Aceite:** tentativas de ler, listar, editar, excluir ou agregar dados de outra clínica falham em todos os módulos.
**Base existente:** `TenantIsolationIT` (isolamento físico) e `SessionAuthenticationIT` (sessão e seleção).

### [ ] CRM-072 — Backup, restauração e operação (P0)
**Depende de:** CRM-003, CRM-070.
**Entregas:** documentação de deploy na VM Lightsail e na Vercel (ADR-022): rewrite `/api`, TLS, firewall, proxy confiável para o IP real do cliente, variáveis de ambiente, backups fora da VM e procedimento de restauração (central e por clínica).
**Aceite:** restauração realizada em ambiente isolado; monitoramento e recuperação operacional documentados.

### [ ] CRM-073 — Revisão de privacidade e LGPD (P0)
**Depende de:** CRM-042, CRM-060.
**Entregas:** inventário de dados, acessos, retenção, exportação/remoção, preferências de comunicação e pendências jurídicas.
**Aceite:** fluxos de dados revisados e riscos tratados ou formalmente bloqueados; não declarar conformidade jurídica sem avaliação adequada.

### [ ] CRM-074 — Piloto com clínicas e ajustes (P1)
**Depende de:** CRM-052, CRM-061, CRM-071, CRM-072, CRM-073.
**Entregas:** onboarding de 2–3 clínicas piloto, roteiro de feedback, métricas de adoção e registro de bugs.
**Aceite:** jornadas críticas demonstradas em uso real autorizado; bloqueadores de lançamento resolvidos.

## Fora do MVP — não iniciar sem autorização

- [ ] FUT-001 — Inbox e integração oficial WhatsApp (Fase 2). Desenho prévio: ADR-005 e ADR-007.
- [ ] FUT-002 — Automações de follow-up e campanhas (Fase 2).
- [ ] FUT-003 — Propostas comerciais avançadas (Fase 2).
- [ ] FUT-004 — Cobrança recorrente automatizada (Fase 2, sujeita a validação).
- [ ] FUT-005 — IA para resumos e sugestões comerciais (Fase 3). Desenho prévio: ADR-006 (pgvector já disponível).
- [ ] FUT-006 — Integrações com prontuários e ERPs (Fase 3).

## Decisões pendentes para o responsável pelo produto

- [-] Nome e identidade do SaaS ("Branly CRM" em uso).
- [x] Stack final, ORM e provedor de autenticação (ADR-001, ADR-015, ADR-017, ADR-018).
- [x] Hospedagem: Lightsail e Vercel (ADR-022).
- [x] E-mail transacional: Resend (ADR-021).
- [x] Uma unidade por clínica no MVP.
- [x] Catálogo de papéis e matriz de permissões (ADR-020, [permissoes.md](docs/permissoes.md)).
- [ ] Planos, preços, limites e política de suspensão.
- [x] Agenda própria (ADR-004).
- [ ] Definições exatas das métricas e atribuição comercial (CRM-050).
- [ ] Política de retenção/exclusão/exportação e validação jurídica.

## Template de execução por tarefa

```md
ID: CRM-XXX
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

> Leia `AGENTS.md`, `docs/planejamento-crm-clinicas-estetica.md` e `TASKS.md`. Inspecione o repositório e confirme o estado das tarefas `[-]`. Proponha a próxima tarefa desbloqueada (hoje: CRM-004, CI de CRM-002 ou CRM-015), com plano, arquivos, riscos e testes, e indique quais papéis de agente participam. Aguarde aprovação antes de implementar.
