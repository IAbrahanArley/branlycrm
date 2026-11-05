# Planejamento do Produto — SaaS CRM para Clínicas de Estética

> Documento de referência para desenvolvimento assistido por agentes de IA.
> Status: planejamento do produto adotado em 05/11/2026 (ADR-019). Fonte de escopo do MVP; substitui o escopo de [escopo.md](escopo.md) onde houver conflito.
> Adaptado ao que já existe no repositório: stack, isolamento e autenticação seguem as ADRs aceitas (seções 2.3, 7, 8 e 15). Ordem e estado de execução ficam em [TASKS.md](../TASKS.md); regras dos agentes em [AGENTS.md](../AGENTS.md).

## 1. Visão do produto

- **Tipo:** SaaS B2B multi-tenant.
- **Segmento:** clínicas de estética facial, corporal, depilação a laser e tratamentos estéticos.
- **Foco:** CRM comercial e relacionamento com pacientes, não prontuário eletrônico.
- **Proposta de valor:** captar e organizar leads, acompanhar negociações, aumentar comparecimento e conversão, e estimular retorno.
- **Modelo de receita:** assinatura mensal por clínica, com possíveis limites de usuários, unidades e funcionalidades.

### Objetivos de negócio

1. Reduzir leads esquecidos e oportunidades sem acompanhamento.
2. Aumentar a conversão de avaliação para tratamento contratado.
3. Medir a eficácia de canais de aquisição e da equipe comercial.
4. Melhorar o relacionamento e a retenção de pacientes.

### Fora do escopo do MVP

- Prontuário clínico, diagnósticos, prescrições e evolução médica.
- Controle de estoque, convênios e financeiro clínico completo.
- Central de WhatsApp integrada e disparos automatizados.
- Inteligência artificial e campanhas complexas.

## 2. Ambientes e perfis

### 2.1 Super Admin da plataforma

- Gerenciar clínicas, status de assinatura, planos e limites.
- Consultar indicadores operacionais do SaaS.
- Gerenciar configurações globais e suporte com acesso auditado e restrito.

### 2.2 Painel da clínica

- Dashboard, contatos, funis, oportunidades, tarefas, agenda e relatórios.
- Configuração de equipe, papéis, procedimentos e etapas comerciais.

### 2.3 Perfis de acesso

| Perfil | Permissões principais |
|---|---|
| Proprietário | Configurações e relatórios de toda a clínica |
| Gerente | Equipe, oportunidades, metas e relatórios |
| Recepcionista | Contatos, agenda e tarefas autorizadas |
| Vendedor | Oportunidades atribuídas, negociações e atividades |
| Profissional | Agenda e comparecimentos autorizados |
| Super Admin | Administração da plataforma, sem acesso irrestrito aos dados dos pacientes |

**Regra:** implementar RBAC e isolamento por clínica em todas as operações de backend e banco de dados. Um usuário pode ter papéis distintos em clínicas diferentes.

**Estado atual:** o banco central aceita hoje os papéis `ADMIN`, `RECEPTION` e `PROFESSIONAL` (V2). Adotar os cinco perfis acima exige migração do catálogo e mapeamento (proposta: Proprietário→`OWNER`, Gerente→`MANAGER`, Recepcionista→`RECEPTION`, Vendedor→`SALES`, Profissional→`PROFESSIONAL`; `ADMIN` atual→`OWNER`). Decisão pendente registrada em CRM-012. Super Admin é autorização própria da plataforma, nunca um papel de clínica.

## 3. Jornada comercial do paciente

1. **Captação:** contato chega por Instagram, WhatsApp, site, anúncio ou indicação.
2. **Cadastro:** equipe registra contato, origem, interesse e responsável.
3. **Atendimento:** equipe registra interações e identifica a demanda comercial.
4. **Agendamento:** avaliação é marcada e confirmada.
5. **Comparecimento:** equipe registra presença, falta, cancelamento ou reagendamento.
6. **Negociação:** oportunidade recebe valor estimado e próxima ação.
7. **Fechamento:** oportunidade é ganha ou perdida, com valor ou motivo de perda.
8. **Relacionamento:** histórico e tarefas ajudam a acompanhar retorno e novas oportunidades.

**Modelagem essencial:** contato/paciente e oportunidade são entidades distintas; um contato pode ter várias oportunidades simultâneas.

## 4. Módulos e priorização

| Módulo | Escopo | Fase |
|---|---|---|
| Dashboard | KPIs, funil resumido, filtros | MVP |
| Contatos | Cadastro, busca, tags, histórico, deduplicação | MVP |
| Funil de vendas | Etapas configuráveis, Kanban, oportunidades | MVP |
| Tarefas | Follow-ups manuais, prazos, responsáveis | MVP |
| Agenda comercial | Avaliações, horários, status, conflitos | MVP |
| Equipe e permissões | Convites, papéis e acessos | MVP |
| Relatórios | Conversão, origem, desempenho, faltas | MVP |
| Administração SaaS | Clínicas, planos e controle de assinatura | MVP mínimo / cobrança automatizada posterior |
| WhatsApp oficial | Caixa de entrada, histórico e templates | Fase 2 |
| Automações | Tarefas e mensagens por eventos | Fase 2 |
| Campanhas | Segmentação e reativação | Fase 2 |
| Propostas e procedimentos | Catálogo, propostas e negociações avançadas | Fase 2 |
| IA | Sugestões, resumos e análises | Fase 3 |
| Integrações clínicas | Prontuários, ERPs e outros sistemas | Fase 3 |

## 5. Requisitos funcionais do MVP

### 5.1 Autenticação, onboarding e tenancy

- Login, logout e recuperação de senha.
- Cadastro da clínica com configurações básicas e fuso horário.
- Convite e remoção de membros; papéis por clínica.
- Seleção da clínica ativa para usuários com múltiplos vínculos.
- Suspensão de acesso conforme status da assinatura, sem perda silenciosa de dados.
- Auditoria de ações administrativas relevantes.

**Aceite:** usuários de clínicas diferentes não conseguem ler, alterar ou inferir dados uns dos outros, inclusive alterando IDs em URLs e requisições.

### 5.2 Contatos

**Campos iniciais sugeridos:** `id`, `clinic_id`, `name`, `phone`, `email`, `source`, `status`, `assigned_user_id`, `created_at`, `updated_at`.

- Criar, editar, listar, buscar e filtrar contatos.
- Normalizar telefone e detectar possíveis duplicidades dentro da clínica.
- Registrar origem (Instagram, WhatsApp, site, indicação, outros).
- Tags e observações comerciais, com restrições a dados sensíveis.
- Histórico cronológico de atividades e oportunidades.
- Preferências de comunicação e informações pertinentes sobre consentimento/base legal.

**Aceite:** um contato pode possuir mais de uma oportunidade; duplicidades geram aviso antes de criar novo registro.

### 5.3 Funil e oportunidades

**Etapas padrão:** Novo lead → Em contato → Avaliação agendada → Avaliação realizada → Em negociação → Venda concluída.

**Estados terminais adicionais:** Perdida e cancelada; não confundir com etapas ativas.

**Campos iniciais sugeridos:** `id`, `clinic_id`, `contact_id`, `pipeline_id`, `stage_id`, `assigned_user_id`, `procedure_id`, `estimated_value`, `closed_value`, `status`, `lost_reason`, `next_action_at`, `closed_at`, `created_at`, `updated_at`.

- Criar oportunidade vinculada a contato e procedimento de interesse.
- Exibir Kanban com cartões e filtros.
- Mover oportunidade entre etapas, validando permissões e regras.
- Registrar valor estimado, responsável e próxima ação.
- Marcar como ganha com valor fechado e data; perdida com motivo obrigatório.
- Registrar eventos de mudança de etapa e fechamento.
- Identificar oportunidades paradas.

**Aceite:** mudança de etapa persiste e aparece no histórico; encerramentos exigem dados apropriados; o sistema impede movimentação entre funis de clínicas diferentes.

### 5.4 Tarefas e follow-ups

- Criar tarefa vinculada a contato e, opcionalmente, oportunidade.
- Responsável, descrição, vencimento, prioridade e status.
- Listar tarefas de hoje, futuras, atrasadas e concluídas.
- Concluir tarefa com registro de resultado.
- Notificações internas simples, se viáveis no MVP.

**Aceite:** tarefa atrasada é identificada usando o fuso da clínica; usuário só visualiza tarefas permitidas.

### 5.5 Agenda comercial

- Calendário diário, semanal e mensal.
- Agendar avaliação com contato, oportunidade, profissional, início e fim.
- Estados: agendado, confirmado, compareceu, faltou, cancelado.
- Reagendamento e histórico de mudanças.
- Bloqueio de conflito de horários conforme regra de disponibilidade.

**Aceite:** dois agendamentos incompatíveis para o mesmo profissional não podem ser confirmados simultaneamente.

### 5.6 Dashboard

- Leads novos no período.
- Oportunidades abertas, ganhas e perdidas.
- Conversão por coorte de leads e/ou oportunidades, com fórmula documentada.
- Avaliações agendadas, realizadas e faltas.
- Valor de oportunidades ganhas (não confundir com receita efetivamente recebida).
- Desempenho por responsável e origem.
- Filtros por período, equipe e origem.

**Aceite:** números do dashboard correspondem aos registros e aos filtros aplicados; as métricas têm definições estáveis.

### 5.7 Relatórios

- Conversão por origem.
- Desempenho por vendedor.
- Motivos de perda.
- Taxa de comparecimento/faltas.
- Vendas comerciais por procedimento.
- Oportunidades sem acompanhamento.

### 5.8 Administração mínima do SaaS

- Cadastro e ativação de clínicas.
- Plano atribuído, limites e status de assinatura.
- Suspensão e reativação com políticas explícitas.
- Cobrança recorrente automática pode ser implementada na Fase 2; no piloto, usar provisionamento administrativo controlado.

## 6. Mapa de telas

```text
/login
/recuperar-senha
/onboarding
/app/dashboard
/app/contatos
/app/contatos/:id
/app/oportunidades
/app/oportunidades/:id
/app/tarefas
/app/agenda
/app/relatorios
/app/configuracoes/clinica
/app/configuracoes/equipe
/app/configuracoes/funis
/app/configuracoes/procedimentos
/admin/clinicas
/admin/planos
/admin/assinaturas
```

As rotas são sugestões e podem ser adaptadas ao framework e à estratégia de tenancy.

## 7. Modelo de dados inicial

| Entidade | Propósito / relações |
|---|---|
| `clinics` | Tenant principal |
| `users` | Identidade global |
| `clinic_memberships` | Usuário ↔ clínica, papel e status |
| `roles` / `permissions` | Permissões configuráveis ou catálogo fixo |
| `contacts` | Contatos pertencentes à clínica |
| `contact_tags` / `tags` | Classificação dos contatos |
| `procedures` | Catálogo comercial de procedimentos |
| `pipelines` | Funis por clínica |
| `pipeline_stages` | Etapas ordenadas de cada funil |
| `opportunities` | Oportunidades vinculadas a contato e funil |
| `opportunity_events` | Mudanças de etapa e encerramentos |
| `activities` | Interações e anotações comerciais |
| `tasks` | Follow-ups e tarefas |
| `appointments` | Avaliações e agendamentos comerciais |
| `audit_logs` | Auditoria de operações relevantes |
| `plans` | Planos comerciais do SaaS |
| `subscriptions` | Plano/status de cada clínica |

### Onde cada entidade vive (ADR-014)

Cada clínica tem **banco PostgreSQL próprio** (`clinic_<uuid>`), acessado só pelo papel runtime dela. Por isso o modelo se divide:

| Banco | Entidades | Nome atual |
|---|---|---|
| Central (controle) | Clínicas, identidades, vínculos, provisionamento, planos, assinaturas, auditoria da plataforma | `tenants`, `user_accounts`, `tenant_memberships`, `tenant_provisioning_tasks`; `plans`/`subscriptions`/`audit_logs` futuros |
| Da clínica | Contatos, tags, procedimentos, funis, etapas, oportunidades, eventos, atividades, tarefas, agendamentos e auditoria de negócio | criadas por `db/tenant-migration` |

`clinics`, `users` e `clinic_memberships` da tabela acima correspondem a `tenants`, `user_accounts` e `tenant_memberships`.

### Regras de modelagem

- IDs UUID; datas em UTC no banco, exibidas no fuso da clínica.
- Entidades da clínica ficam no banco da clínica e **não precisam de `clinic_id`**: o isolamento é físico, por banco e credencial. Uma consulta sem filtro não alcança outra clínica.
- Referências cruzadas (contato, oportunidade, etapa, tarefa, agenda) são FKs dentro do mesmo banco da clínica. Referências a usuários usam o `subject_id` global e são validadas contra a participação ativa no banco central.
- O tenant ativo vem da participação autorizada do usuário autenticado, nunca de parâmetro do cliente.
- Soft delete ou política de retenção quando necessário, evitando apagamento não intencional de histórico.
- Criar índices para `clinic_id`, responsáveis, status, datas e filtros comuns.
- Valores monetários em centavos inteiros ou `NUMERIC` com precisão definida.
- Registrar alterações relevantes de etapa, responsável e fechamento.

## 8. Stack e arquitetura adotadas

A sugestão original (NestJS, Supabase Auth, Redis/BullMQ) foi substituída pelas decisões já aceitas e implementadas:

| Camada | Adotado | Referência |
|---|---|---|
| Frontend | Next.js 16 + React 19 + TypeScript | ADR-012 |
| UI | Tailwind CSS + shadcn/ui | ADR-012 |
| Backend | Java 21 + Spring Boot 4.1 (Spring MVC REST), monólito modular | ADR-001, ADR-017 |
| Banco | PostgreSQL 17 + pgvector; banco central e um banco por clínica; Flyway | ADR-009, ADR-014 |
| Autenticação | Spring Security com sessão de servidor, cookie HttpOnly e CSRF | ADR-015 |
| Jobs | Processos Java separados e fila persistente no PostgreSQL (ex.: provisionador); Redis/broker só com necessidade real | ADR-005, ADR-018 |
| Ambiente local | Docker Compose com Nginx como proxy | ADR-013 |
| Deploy | Pendente: plataforma com containers e PostgreSQL gerenciado | CRM-001 |
| Mensageria futura | WhatsApp Business Platform oficial | Fase 2 (ADR-005/007 como desenho prévio) |
| Cobrança futura | Asaas, Pagar.me ou Stripe, conforme validação | Fase 2 |

**Estratégia:** monólito modular multi-tenant, pacote `br.com.branlycrm`. Módulos existentes: `identity`, `tenant`, `provisioning`, `config`. Módulos planejados no mesmo padrão: `contacts`, `sales`, `tasks`, `scheduling`, `analytics`, `billing`, `audit`, `platform`. Camadas e convenções em [padroes-backend.md](padroes-backend.md). Sem microsserviços prematuros.

## 9. Segurança, LGPD e qualidade

- Aplicar princípio do menor privilégio e verificar autorização no servidor.
- Proteger dados pessoais em trânsito e em repouso.
- Dados de saúde são sensíveis; não inserir informações clínicas desnecessárias no CRM comercial.
- Definir responsabilidades de controlador/operador, bases legais, retenção e atendimento aos direitos dos titulares com apoio jurídico adequado.
- Registrar e respeitar preferências de comunicação e regras aplicáveis a campanhas.
- Implementar rate limiting, validação, proteção contra ataques comuns e gestão de segredos.
- Backups automáticos com restauração testada.
- Logs e métricas sem exposição desnecessária de dados pessoais.
- Testes automatizados para isolamento entre clínicas, RBAC e regras críticas.

## 10. Roadmap sugerido (12–16 semanas, estimativa)

| Período | Entregas |
|---|---|
| Semanas 1–4 | Arquitetura, tenancy, autenticação, permissões e onboarding |
| Semanas 4–8 | Contatos, procedimentos, funil e oportunidades |
| Semanas 8–11 | Tarefas, atividades e agenda comercial |
| Semanas 11–13 | Dashboard e relatórios |
| Semanas 13–16 | Testes, segurança, correções, pilotos e lançamento |

As fases podem se sobrepor. Estimativas dependem da equipe e do nível de acabamento.

## 11. Critérios de aceite para lançamento

- [ ] Uma clínica não acessa dados de outra, inclusive por IDs manipulados.
- [ ] Papéis e permissões são aplicados no backend.
- [ ] Recepcionista cadastra contato e cria oportunidade.
- [ ] Vendedor move oportunidade e registra histórico.
- [ ] Venda ganha registra valor; perda exige motivo.
- [ ] Agenda detecta conflitos e registra comparecimento/falta.
- [ ] Tarefas vencidas e oportunidades paradas são identificadas.
- [ ] Dashboard e relatórios apresentam métricas coerentes.
- [ ] Auditoria registra ações críticas.
- [ ] Backup de teste pode ser restaurado.
- [ ] Fluxos essenciais funcionam em desktop e celular.

## 12. Validação com clientes

- Entrevistar de 5 a 10 clínicas de estética.
- Mapear origem dos contatos, processo de atendimento, agendamentos e perdas.
- Validar o vocabulário das etapas comerciais e os indicadores relevantes.
- Pilotar com 2 a 3 clínicas reais antes de ampliar escopo.
- Medir adoção semanal, leads acompanhados, tempo até primeiro contato, taxa de agendamento, comparecimento e conversão.

## 13. Fases futuras

### Fase 2

- Caixa de entrada e integração oficial do WhatsApp.
- Automações de follow-up, confirmação e reativação.
- Campanhas segmentadas e gestão de templates.
- Propostas comerciais e negociação avançada.
- Cobrança recorrente automática, limites por plano e autosserviço.

### Fase 3

- IA para resumos, sugestões de respostas e análises comerciais.
- Integrações com sistemas clínicos e ERPs.
- Análises avançadas de coortes, retenção e performance.

## 14. Instruções para agentes de desenvolvimento

### Fonte de verdade

As regras operacionais dos agentes, inclusive multiagentes, estão em [AGENTS.md](../AGENTS.md) e prevalecem sobre esta seção. Este arquivo descreve a intenção do produto. Antes de implementar, o agente deve inspecionar o repositório, identificar convenções existentes e propor um plano pequeno e verificável. Não presumir que tabelas, endpoints ou bibliotecas já existem.

### Ordem recomendada de implementação

1. Criar estrutura de projeto, configurações e testes.
2. Implementar tenancy, autenticação e autorização.
3. Implementar schema/migrations e CRUD de contatos.
4. Implementar funis, etapas e oportunidades.
5. Implementar atividades, tarefas e agenda.
6. Implementar métricas, dashboard e relatórios.
7. Implementar administração SaaS mínima.
8. Executar testes end-to-end e revisão de segurança.

### Regras para agentes

- Não desenvolver funcionalidades da Fase 2 ou 3 sem solicitação explícita.
- Nunca confiar em identificador de clínica enviado pelo cliente sem verificar a participação do usuário autenticado.
- Toda query de dados de negócio roda no banco da clínica autorizada (`TenantConnectionRepository`), nunca no banco central, e respeita o papel do usuário.
- Criar migrations versionadas; não alterar banco de produção manualmente.
- Escrever testes para regras de negócio e cenários entre tenants.
- Evitar armazenar dados clínicos/sensíveis desnecessários.
- Antes de alterações estruturais, apresentar arquivos afetados, impactos e estratégia de testes.
- Manter documentação de decisões arquiteturais e contratos de API.
- Após cada entrega, informar arquivos alterados, testes executados, pendências e riscos.

### Modelo de tarefa para delegar ao agente

```md
Objetivo: implementar [módulo / funcionalidade].
Contexto: seguir planejamento-crm-clinicas-estetica.md.
Escopo: [descrever comportamento esperado].
Fora de escopo: [listar exclusões].
Critérios de aceite:
- [ ] [resultado observável 1]
- [ ] [resultado observável 2]
Segurança: validar tenant, papel e dados de entrada.
Testes: unitários, integração e cenário de isolamento quando aplicável.
Entrega: código, migrations, testes e breve documentação.
```

## 15. Decisões em aberto

- [-] Nome e identidade do produto: "Branly CRM" em uso no código e na interface; confirmação comercial pendente.
- [x] Estratégia de login e autenticação: sessão de servidor com Spring Security (ADR-015). Recuperação, convite e segundo fator ainda a implementar.
- [x] Política de multiunidades: uma unidade por clínica no MVP, estrutura preparada para expansão ([escopo.md](escopo.md)).
- [ ] Modelo de cobrança, preços e limites dos planos.
- [x] Agendamento nativo no CRM (ADR-004).
- [ ] Catálogo final de papéis (ver seção 2.3 e CRM-012).
- [ ] Campos e nomenclatura final dos procedimentos.
- [ ] Definições exatas das métricas e atribuição de vendas.
- [ ] Política de retenção, exportação e exclusão de dados.
- [ ] Requisitos legais e contratos para tratamento de dados pessoais.

---

**Próximo passo sugerido:** transformar o módulo de contatos e o funil em histórias de usuário, contratos de API, migrations e testes, mantendo cada entrega pequena o suficiente para revisão humana.
