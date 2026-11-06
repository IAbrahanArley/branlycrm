# Registros de decisões arquiteturais

## ADR-010 — MVC REST com contrato documentado na interface

> Substituída pela ADR-017 em 05/11/2026 quanto à interface no arquivo do controller. O texto abaixo permanece como histórico.

**Data:** 05/11/2026. **Status:** Aceita por instrução do usuário.

**Contexto:** Padronizar controllers e manter contrato HTTP e documentação junto à implementação.

**Decisão:** Spring MVC REST dentro do monólito modular. Cada arquivo de controller contém uma interface de pacote com assinaturas, mapeamentos, validações de parâmetros e anotações OpenAPI, seguida de uma classe pública concreta com RestController, implements e Override. A implementação delega a services por injeção de construtor. DTOs e services têm arquivos próprios. Detalhes em ../backend-mvc.md.

**Alternativas:** Contrato em arquivo separado; documentação e mapeamentos na implementação; controllers com regras de negócio.

**Consequências:** Apenas a classe concreta pode ser pública no arquivo. O contrato não poderá ser importado de outro pacote; se isso se tornar necessário, uma nova decisão deverá separar arquivos. Regras e transações permanecem no service; persistência no repository. Validar rotas, validação e OpenAPI em teste de integração do primeiro endpoint.

**Rastreabilidade:** ADR-001; todos os endpoints dos cenários.

Data de todos os registros: 04/11/2026.

## ADR-001 — Arquitetura modular e stack

**Status:** Aceita.

**Contexto:** O MVP precisa integrar CRM, agenda, WhatsApp e IA sem multiplicar operações de infraestrutura.

**Decisão:** Monólito modular em Java/Spring Boot; frontend Next.js com shadcn/ui; PostgreSQL. Regras de negócio e autorização ficam no backend. Módulos: acesso/equipe, clínicas, CRM, conversas, agenda, conhecimento, IA, automações e assinatura. Worker Java executa tarefas demoradas.

**Alternativas:** Microsserviços desde o início; regras distribuídas entre frontend e backend.

**Consequências:** Menos serviços para operar; exige fronteiras claras entre módulos. Versões, hospedagem e mecanismo de atualização da interface ainda serão selecionados.

**Rastreabilidade:** Todos os SCs.

## ADR-002 — Isolamento entre clínicas

> Substituída pela ADR-014 em 05/11/2026. O texto abaixo permanece como histórico.

**Status:** Aceita.

**Contexto:** Várias clínicas utilizarão a plataforma, inclusive a base de conhecimento e o processamento em segundo plano.

**Decisão:** Banco compartilhado com tenant_id nos registros pertencentes a clínicas. O contexto da clínica deve resultar de uma participação autorizada do usuário ou da integração, nunca apenas de um parâmetro enviado pelo cliente. Aplicar isolamento também a arquivos, vetores, eventos, consumo e tarefas.

**Alternativas:** Banco ou schema separado por clínica.

**Consequências:** Operação inicial mais simples; isolamento exige verificação sistemática. Mecanismo adicional de proteção no banco será definido antes da implementação.

**Rastreabilidade:** SC-003, SC-010, SC-015.

## ADR-003 — Identidade, participação e profissional

**Status:** Aceita.

**Contexto:** Recepcionistas acessam o CRM sem receber procedimentos; profissionais podem ter agenda sem login.

**Decisão:** Separar usuário, participação na clínica e profissional. Vínculo usuário/profissional é opcional. Cadastro público cria clínica e administrador; demais acessos são concedidos por convite. Papéis iniciais: administrador, recepção/comercial e profissional. Identidade pode participar de várias clínicas, com papéis próprios.

**Alternativas:** Um cadastro único de funcionário com acesso obrigatório; módulo completo de RH.

**Consequências:** Desativar participação revoga o acesso à clínica e preserva autoria histórica. Reatribuição de trabalho e bloqueio da agenda são operações explícitas. Login por e-mail/senha, recuperação, verificação de e-mail e segundo fator para administradores fazem parte do requisito; provedor e política de sessão permanecem pendentes.

**Rastreabilidade:** SC-001 a SC-004.

## ADR-004 — Agenda própria e integridade de reservas

**Status:** Aceita.

**Contexto:** Agendamentos conectam o atendimento ao comparecimento e à venda.

**Decisão:** Agenda no CRM, com serviço, duração, profissional, unidade, fuso e recursos opcionais. Backend utiliza a mesma operação transacional para reservas feitas pela IA e pela equipe. Impedir sobreposição de profissional, sala ou equipamento. Consultar disponibilidade não reserva horário. MVP com um serviço e um profissional por agendamento.

**Alternativas:** Agenda externa; validação apenas na interface; reserva em múltiplas etapas sem proteção concorrente.

**Consequências:** Integridade deve ser garantida inclusive sob concorrência. Persistir instantes e fuso da unidade; detalhes das restrições de banco serão definidos no desenho técnico. Reagendamento e cancelamento pela IA ficam fora do MVP.

**Rastreabilidade:** SC-005 a SC-007, SC-011.

## ADR-005 — WhatsApp e processamento confiável

**Status:** Aceita.

**Contexto:** Mensagens podem chegar repetidas, atrasadas e durante indisponibilidades dos fornecedores.

**Decisão:** Usar API oficial do WhatsApp, diretamente ou por provedor a selecionar. Processar eventos em segundo plano, com identificação de duplicidade, estado persistente, tentativas e registro de falha. Inicialmente, fila persistente no PostgreSQL; Redis e broker dedicado não são dependências obrigatórias. Usar armazenamento compatível com S3 para arquivos.

**Alternativas:** Integração não oficial; processamento apenas síncrono; introdução imediata de broker dedicado.

**Consequências:** Regras do canal e modelos aprovados condicionam o envio. Timeout de envio é resultado incerto, não prova de falha: reconciliar antes de repetir quando houver risco de duplicidade. Não se promete entrega exatamente uma vez pelo provedor.

**Rastreabilidade:** SC-008, SC-009, SC-013.

## ADR-006 — RAG por clínica e ações controladas

**Status:** Aceita.

**Contexto:** A IA precisa responder com conhecimento específico e executar ações sem inventar dados ou confirmações.

**Decisão:** PostgreSQL com pgvector para recuperação de conhecimento aprovado e versionado por clínica. Separar conhecimento documental, consultas operacionais e ações autorizadas. Agenda e preços vigentes são consultados nos dados operacionais. Modelo e embeddings serão selecionados após prova técnica. IA não fornece diagnóstico, prescrição ou indicação clínica individual.

**Alternativas:** Prompt fixo com todo o conteúdo; banco vetorial separado; acesso irrestrito do modelo ao banco.

**Consequências:** Exige ingestão, avaliação de recuperação e rastreio das fontes usadas. Conteúdo recuperado é dado, não autoridade para alterar regras ou permissões. Histórico de conversas não vira conhecimento aprovado automaticamente.

**Rastreabilidade:** SC-010, SC-011, SC-012.

## ADR-007 — Transferência humana como estado persistente

**Status:** Aceita.

**Contexto:** A IA deve ceder controle sem responder junto com a equipe.

**Decisão:** Estados: IA atendendo, aguardando equipe, humano atendendo e encerrado. Pedido do cliente, ausência de informação, conflito de fontes, exceção comercial, reclamação, falhas ou assunto clínico provocam transferência. Gerar resumo e motivo. Backend revalida propriedade da conversa antes do envio automático. A retomada pela IA exige ação explícita autorizada da equipe.

**Alternativas:** Transferência decidida só no texto da IA; retomada automática por tempo.

**Consequências:** A fila deve invalidar respostas pendentes quando o controle muda. Envios já aceitos pelo provedor podem estar em trânsito e devem permanecer identificáveis no histórico.

**Rastreabilidade:** SC-009, SC-012.

## ADR-008 — Modelo comercial e medição

**Status:** Direção aceita; valores e limites pendentes.

**Contexto:** O custo inclui operação fixa, IA e canal de mensagens.

**Decisão:** Mensalidade por clínica/unidade, usuários incluídos e franquia de IA. Unidade comercial inicial: contato atendido pela IA no mês, contado uma vez por clínica no período. Medir internamente processamento e custo. Custos do WhatsApp precisam de tratamento comercial explícito.

**Alternativas:** Somente por usuário; IA ilimitada sem medição; cobrança por tokens apresentada ao cliente.

**Consequências:** Definir calendário de apuração, excedentes, limites de uso e comportamento no vencimento antes da venda. Sem excedente autorizado, atingir limite encaminha novas demandas ao humano. Cobrança automática depende de provedor ainda não escolhido.

**Rastreabilidade:** SC-015.

## ADR-009 — Operação e evolução do banco

**Status:** Aceita.

**Contexto:** Falhas precisam ser observáveis e alterações de estrutura reproduzíveis.

**Decisão:** Flyway para migrações; logs, métricas, acompanhamento de erros, auditoria de ações relevantes e backups com restauração verificada. Credenciais protegidas e fora dos registros de log. Acesso administrativo à plataforma deve ter autorização e auditoria próprias.

**Alternativas:** Mudanças manuais no banco e diagnóstico exclusivamente por relato dos usuários.

**Consequências:** Retenção de dados, metas de recuperação e ferramentas operacionais ainda precisam ser definidas. Não registrar indiscriminadamente o conteúdo sensível em logs.

**Rastreabilidade:** SC-016.

## ADR-011 — Monorepo e planejamento separado do código

**Data:** 05/11/2026. **Status:** Adotada na organização atual.

**Contexto:** Backend, frontend e contratos evoluem em conjunto no MVP.

**Decisão:** Manter backend, frontend, docs e configuração futura de infraestrutura no mesmo repositório. Dependências e publicação das aplicações podem ser independentes. GitHub Projects poderá organizar trabalho e não substitui o repositório. Não foi criado um Project nesta etapa.

**Alternativas:** Repositório por aplicação desde o início.

**Consequências:** Mudanças entre camadas podem ser revisadas juntas. Separação futura pode ser considerada com equipes, permissões ou ciclos de produto distintos. SCs e ACs servem como referência das tarefas.

## ADR-012 — Localização do frontend e base inicial

**Data:** 05/11/2026. **Status:** Aceita por instrução do usuário.

**Contexto:** O frontend foi inicialmente criado em outra pasta.

**Decisão:** A raiz do repositório é C:\Users\abrah\OneDrive\Área de Trabalho\crm\api e o frontend está em frontend/. Usar Next.js com App Router, TypeScript, Tailwind e shadcn/ui, com npm e ESLint. Backend permanece Java/Spring MVC. Não implementar regras de negócio duplicadas no frontend.

**Alternativas:** Manter projeto no caminho anterior; repositório separado para frontend.

**Consequências:** Frontend movido e registrado no commit 557a1ca. Compilação de produção permanece pendente de validação. Fontes do sistema evitam download obrigatório durante build. Versões instaladas e situação detalhadas em ../projeto.md.

## ADR-013 — Contêineres e Nginx como proxy

**Data:** 05/11/2026. **Status:** Adotada nesta implementação.

**Contexto:** Executar frontend, API e banco juntos, com um endereço de acesso.

**Decisão:** Compose na raiz com PostgreSQL 17/pgvector, backend Java 21, Next.js standalone em Node 24 e Nginx como proxy de entrada. /api/ é encaminhado sem remover o prefixo; perfil docker do Spring define context-path /api. Apenas Nginx publica porta local. Banco persistente em volume, credenciais via ambiente e healthchecks para ordenar inicialização. Flyway habilita vector. Containers de aplicações executam sem root.

**Alternativas:** Exportação estática de Next servida pelo Nginx; exposição independente de frontend/API/banco; Compose separado no backend.

**Consequências:** Nginx não substitui o servidor Next. Chamadas do navegador usam origem comum; chamadas internas usam nomes dos serviços. O contexto /api deve ser considerado nos futuros mapeamentos para evitar /api/api. O prefixo padrão das controllers passa a /v1, relativo ao contexto da aplicação. Autenticação completa permanece pendente; apenas saúde é pública. Detalhes em ../docker.md.

## ADR-014 — Banco exclusivo por clínica e registro central

**Data:** 05/11/2026. **Status:** Aceita por instrução do usuário. **Substitui:** ADR-002 quanto ao banco compartilhado de dados comerciais.

**Decisão:** Cada clínica recebe UUID permanente e banco PostgreSQL próprio. Um banco central guarda apenas registro, estado de provisionamento e participações; dados comerciais, conversas, agenda e vetores pertencem aos bancos das clínicas. Cada banco terá credencial própria e papel sem privilégios administrativos. O tenant_id identifica a clínica e não funciona como senha.

**Implementação inicial:** Registro central com nomes de banco/papel derivados do UUID e imutáveis, referências de segredo sem senha no banco, estados PROVISIONING/ACTIVE/SUSPENDED/FAILED, autorização por participação ativa e conexão explícita com conferência de identidade no banco de destino. Não há fallback para o banco central nem tenant padrão. Credenciais são resolvidas na configuração do servidor, nunca recebidas do navegador.

**Consequências:** Migrações separadas para banco central e bancos de clínicas; provisionamento, migração e restauração por clínica exigem orquestração. O registro só pode virar ACTIVE após banco, identidade, migrações e permissões verificados. Não realizar CREATE DATABASE dentro de transação de cadastro: PostgreSQL exige operação fora de transação, com retomada e compensação. UUID deve ser preservado entre tentativas.

**Limites atuais:** Fundação implementada; cadastro/login, provisionador de banco/papel, rotação de segredos, pools limitados e integração JPA multitenant ainda serão implementados. Nenhum endpoint permite selecionar tenant por header. O banco central atual continua com credencial administrativa do ambiente Docker local; antes de expor cadastro ou dados reais, separar papel de execução do papel provisionador. Entidades de domínio NÃO devem usar o EntityManager central. Workers e IA precisam de identidade de serviço e autorização próprias.

**Qualidade:** Constraints, relações e índices no banco central; conferência de UUID no banco conectado; ausência/inatividade/segredo ausente causam falha fechada. Detalhes em ../multitenancy.md.

## ADR-015 — Identidade global e autenticação por sessão

**Data:** 05/11/2026. **Status:** Adotada para a primeira entrega de acesso.

**Decisão:** Identidade global no banco central, participação/papel por clínica, autenticação por sessão de servidor com cookie HttpOnly/SameSite e CSRF habilitado. Usar filtros nativos de login/logout com respostas HTTP sem redirects. Sem JWT em armazenamento do navegador. Seleção de clínica exige participação ativa e não atribui autoridade global. Versão e estado da conta são revalidados em cada requisição; tenant selecionado também é revalidado.

**Alternativas:** JWT em localStorage; confiar em tenant informado por header; atribuir papel de clínica globalmente ao usuário.

**Consequências:** Sessões atualmente em memória atendem uma instância e se perdem no reinício; armazenamento compartilhado é necessário para escalar. HTTPS e Secure cookie necessários na hospedagem. Limitação de login é local inicialmente. Login/logout executados por filtros constituem operações de infraestrutura e não recebem implementação fictícia de controller; controllers reais preservam interface documentada e Override. Cadastro, MFA e recuperação ainda pendentes. Detalhes em ../autenticacao.md.

## ADR-016 — Separação de credenciais e migração fora da API

**Data:** 05/11/2026. **Status:** Implementada no Docker local.

**Decisão:** API usa crm_control_runtime; processo independente executa migrações com crm_control_migrator; bootstrap administrativo prepara permissões; crm_tenant_provisioner fica reservado ao futuro provisionamento, sem acesso ao banco central. API não recebe segredos desses outros papéis. Bootstrap também atende volumes existentes, transferindo apenas objetos conhecidos da aplicação.

**Alternativas:** Usuário administrativo compartilhado; senha de migração carregada pelo processo HTTP.

**Consequências:** Subida exige bootstrap e migração concluídos. Novas tabelas precisam de grants explícitos. O provisionador ainda não foi implementado e as credenciais por clínica continuam pendentes. Consultas de autorização também verificam conta ativa/verificada fora do filtro HTTP. Procedimentos e testes em ../credenciais-postgres.md.

## ADR-017 — Padrão de código do Smart Commerce

**Data:** 05/11/2026. **Status:** Aceita por instrução do usuário. **Substitui:** ADR-010 quanto à interface de contrato no arquivo do controller.

**Contexto:** O usuário pediu que o backend siga a arquitetura, a organização de pastas, os testes e a qualidade de código do projeto Smart Commerce, aproveitando o início do projeto.

**Decisão:** Pacote raiz br.com.branlycrm, com módulos identity, tenant e config. Camadas dentro de cada módulo: controllers, services, repositories, domain, dto/request, dto/response, exceptions e, na identidade, security. Controllers são classes finais com anotações OpenAPI nos próprios métodos e um RestControllerAdvice por controller; não há mais interface ControllerDoc. Exceções de negócio não conhecem HTTP. Spotless com google-java-format, ArchUnit para dependências entre camadas, testes unitários *Test e de integração *IT com Testcontainers via Failsafe. Detalhes em ../padroes-backend.md e ../qualidade.md.

**Preservado:** rotas e campos JSON da sessão, login/logout pelos filtros do Spring Security, CSRF, cookie, revalidação por auth_version, limitação de tentativas, migrações V1–V4 e tenant V1, configuração Docker e credenciais (ADR-013 a ADR-016).

**Diferenças em relação ao Smart Commerce:** login continua em filtro nativo, não em controller com JSON; sessões continuam em memória, sem Spring Session JDBC; Swagger não foi restrito a um perfil dev porque o projeto ainda não tem esse perfil. Essas convergências exigem decisões próprias.

**Consequências:** A conexão com o banco da clínica passou de service para TenantConnectionRepository, que verifica a participação antes de ler o segredo, porque services não podem depender de java.sql. Respostas 403 da seleção de clínica passaram a ter corpo {"message"}; UUID malformado responde 400. Dependências sem uso (mail, devtools) foram removidas e voltam quando o fluxo de e-mail for implementado.

## ADR-018 — Provisionador de clínicas e pools por tenant

**Data:** 05/11/2026. **Status:** Implementada no Docker local. **Complementa:** ADR-014 e ADR-016.

**Decisão:** Processo separado (`APP_MODE=provision`, sem HTTP) com fila persistente `tenant_provisioning_tasks` no banco central, posse por `FOR UPDATE SKIP LOCKED` e lease, etapas idempotentes e retomáveis. Recebe apenas `crm_provisioning_worker` (banco central, sem acesso a hashes) e `crm_tenant_provisioner` (CREATEDB/CREATEROLE, sem superusuário e sem acesso ao banco central). Por clínica: `owner_<id>` NOLOGIN dono de tudo e `tenant_<id>` runtime; o provisionador tem SET sem INHERIT sobre o dono e aplica as migrações como dono. A extensão vector é preparada em `template1` pelo bootstrap, porque não é trusted. A clínica só fica ACTIVE após verificação por login real do runtime. A API usa pools Hikari limitados por clínica e globalmente, chaveados pela versão do segredo.

**Alternativas:** Papel `migrator_<id>` com login (segunda senha por clínica sem ganho de isolamento); superusuário no provisionador; executar o provisionamento no processo HTTP; um pool sem limite por clínica.

**Consequências:** O provisionador é uma autoridade privilegiada sobre todas as clínicas e deve ser tratado como operação administrativa. A persistência JPA de clínica foi adiada até existir a primeira entidade de clínica: um segundo EntityManagerFactory desliga a autoconfiguração JPA central, e sem entidade não há o que validar. Até lá, o ArchUnit proíbe `@Entity`. O cadastro público ainda não cria tarefas. Detalhes em ../provisionamento-clinicas.md.

## ADR-019 — Planejamento do produto e escopo do MVP

**Data:** 05/11/2026. **Status:** Aceita por instrução do usuário. **Substitui:** o escopo de ../escopo.md e a sequência de entregas de ../entregas-e-pendencias.md onde houver conflito.

**Contexto:** O usuário forneceu um planejamento de produto, um backlog executável e instruções para agentes, pedindo que o planejamento passe a ser seguido e adaptado ao que já existe.

**Decisão:** ../planejamento-crm-clinicas-estetica.md é a fonte de escopo do MVP, e ../../TASKS.md é o backlog com o estado verificado. O MVP é CRM comercial: contatos, funil e oportunidades, tarefas, agenda comercial, dashboard, relatórios e administração mínima. WhatsApp, automações e campanhas passam para a Fase 2 e a IA para a Fase 3; as ADR-005, ADR-006 e ADR-007 ficam como desenho prévio dessas fases. Prevalecem as decisões técnicas já aceitas: Java/Spring em vez de NestJS, sessão de servidor em vez de Supabase Auth, banco por clínica em vez de `clinic_id` em banco compartilhado. A execução passa a seguir ../../AGENTS.md, com papéis multiagente em ../agents/ e `.claude/agents/`.

**Consequências:** O modelo comercial da ADR-008 (franquia de IA) fica sem objeto no MVP; a precificação por clínica continua pendente. Os papéis do planejamento (Proprietário, Gerente, Recepcionista, Vendedor, Profissional) diferem do catálogo atual (`ADMIN`, `RECEPTION`, `PROFESSIONAL`) e exigem decisão e migração em CRM-012. Os documentos antigos ganham aviso de substituição e permanecem como histórico.

## ADR-020 — Catálogo de papéis da clínica

**Data:** 05/11/2026. **Status:** Aceita pelo usuário; implementação em CRM-012. **Substitui:** os papéis iniciais da ADR-003 (administrador, recepção/comercial, profissional).

**Decisão:** Cinco papéis por clínica, um por participação: `OWNER` (Proprietário), `MANAGER` (Gerente), `RECEPTION` (Recepcionista), `SALES` (Vendedor) e `PROFESSIONAL` (Profissional). O `ADMIN` atual é convertido em `OWNER`, e o provisionador passa a criar `OWNER`. Responsabilidades, matriz de permissões e invariantes, entre elas a de que toda clínica ativa tem ao menos um OWNER, estão em ../permissoes.md. Super Admin continua fora das participações de clínica.

**Alternativas:** Manter três papéis (não separa venda de recepção, como exige o planejamento); permissões configuráveis por clínica (complexidade sem demanda no MVP).

**Consequências:** Migração do banco central e alteração do provisionador; cada módulo de negócio implementa as políticas da matriz com testes de permissão e de negação. A separação recepção/venda faz parte dos critérios de lançamento do planejamento.

## ADR-021 — Envio de e-mail com Resend

**Data:** 05/11/2026. **Status:** Aceita pelo usuário; implementação em CRM-014 e CRM-011.

**Decisão:** E-mails transacionais (verificação de e-mail, convite e recuperação de senha) são enviados pela API HTTP do Resend, com o domínio do usuário já verificado no Resend. Usar um adaptador HTTP no módulo de identidade, atrás de uma interface de envio, sem SDK novo, no mesmo padrão do Smart Commerce. Em desenvolvimento e testes, um adaptador local grava as mensagens em diretório e nunca envia a pessoas reais.

**Configuração** (somente no processo da API; nunca no frontend nem no provisionador):

| Variável | Uso |
|---|---|
| `MAIL_PROVIDER` | `local` (padrão) ou `resend` |
| `RESEND_API_KEY` | Chave de envio do Resend; segredo, só no `.env` e no gerenciador de segredos da hospedagem |
| `MAIL_FROM_ADDRESS` | Remetente no domínio verificado, por exemplo `no-reply@<domínio>` |
| `MAIL_FROM_NAME` | Nome exibido, por exemplo `Branly CRM` |
| `APP_PUBLIC_ORIGIN` | Origem pública HTTPS usada nos links dos e-mails |

**Consequências:** A chave nunca aparece em logs nem em respostas; falha de envio vira um código estável. Links usam apenas `APP_PUBLIC_ORIGIN` e tokens de uso único armazenados como hash. Uma origem HTTPS exige `SESSION_COOKIE_SECURE=true`. O `.env.example` recebe essas variáveis junto com a implementação.

## ADR-022 — Hospedagem: AWS Lightsail para a API e Vercel para o frontend

**Data:** 05/11/2026. **Status:** Aceita pelo usuário; preparação operacional em CRM-072. **Complementa:** ADR-013.

**Decisão:** Uma VM AWS Lightsail roda o Compose do backend: PostgreSQL com controle total dos papéis, `db-bootstrap`, `migrate`, API, provisionador e o volume de segredos compartilhado, com Nginx e TLS como entrada. O Next.js fica na Vercel enquanto o plano atender. Os dois usam subdomínios do mesmo domínio, por exemplo `app.<domínio>` na Vercel e `api.<domínio>` no Lightsail.

**Por quê:** O banco por clínica exige CREATEDB/CREATEROLE, a extensão vector preparada em `template1` e um volume compartilhado entre provisionador e API (ADR-014, ADR-016, ADR-018). Uma VM própria atende isso sem mudar a arquitetura e com o menor custo. O Postgres gerenciado e os discos por serviço do Render não garantem esses requisitos.

**Consequências e itens obrigatórios antes de expor dados reais:**
- **Mesma origem para o navegador:** a Vercel reescreve `/api/*` para a API (rewrite de proxy). Assim o cookie `BRANLY_SESSION` e o CSRF continuam de primeira parte, sem CORS com credenciais. Chamar `api.<domínio>` direto do navegador exigiria CORS restrito à origem do app.
- **IP real do cliente:** atrás da Vercel, `getRemoteAddr()` passa a ser o IP da Vercel, e o limite de tentativas de login trataria todos os usuários como um só. É preciso confiar no encaminhamento apenas da origem conhecida (cabeçalho do proxy com segredo compartilhado ou IPs da Vercel) e nunca aceitar `X-Forwarded-For` de qualquer um. Hoje o perfil docker usa `forward-headers-strategy: framework`, que confia em qualquer cliente que alcance a API; a porta da API não pode ficar pública.
- **HTTPS:** `SESSION_COOKIE_SECURE=true`, `APP_PUBLIC_ORIGIN=https://app.<domínio>`, certificado no Nginx da VM e portas do PostgreSQL e da API fechadas no firewall do Lightsail.
- **Operação sob nossa responsabilidade:** backup diário do banco central e de cada banco de clínica fora da VM, com restauração testada (CRM-072), atualizações do sistema, monitoramento e snapshots.
- **Plano da Vercel:** o plano Hobby é para uso pessoal e não comercial. Usar com clínicas pagantes exige o plano Pro, mesmo sem necessidade técnica de upgrade.
- **Tamanho da VM:** 4 GB de RAM para três JVMs, PostgreSQL e Nginx. Sem o Next.js na VM, 2 GB pode bastar no início, desde que se meça o consumo antes de reduzir.

## ADR-023 — Contrato de erros, paginação e validação da API

**Data:** 05/11/2026. **Status:** Adotada em CRM-004. **Detalhes:** ../api-convencoes.md.

**Decisão:** Todo erro usa o corpo `{code, message, fields}`, com `code` estável para o frontend, inclusive nas respostas dos filtros de segurança e no fallback global. Listas são paginadas com `page` (a partir de 1) e `pageSize` (25 por padrão, no máximo 100), até a linha 10.000, e respondem `{items, page, pageSize, total}`. Filtros são parâmetros de query validados em records de consulta do domínio. A validação de entrada fica nos records de domínio, e não em Bean Validation como previa a entrega original de CRM-004, porque regras de Unicode, normalização e limites não cabem em anotações e o padrão do projeto de referência (ADR-017) já faz assim.

**Alternativas:** Manter só `{message}` (frontend dependeria de texto); paginação por cursor em toda lista (complexidade sem necessidade nas listas atuais; continua opção por módulo); Bean Validation nos DTOs.

**Consequências:** O frontend trata erros por `code`. Novos códigos são acréscimos ao contrato; mudar o significado de um código exige nova versão. A dependência `spring-boot-starter-validation` fica sem uso até haver necessidade concreta.
