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

> Suspensa pela ADR-024 em 06/11/2026: o MVP não tem agenda própria. O funil registra Agendado e Compareceu, e a agenda integrada é sugestão. O desenho abaixo vale se a agenda voltar ao escopo.

**Status:** Aceita.

**Contexto:** Agendamentos conectam o atendimento ao comparecimento e à venda.

**Decisão:** Agenda no CRM, com serviço, duração, profissional, unidade, fuso e recursos opcionais. Backend utiliza a mesma operação transacional para reservas feitas pela IA e pela equipe. Impedir sobreposição de profissional, sala ou equipamento. Consultar disponibilidade não reserva horário. MVP com um serviço e um profissional por agendamento.

**Alternativas:** Agenda externa; validação apenas na interface; reserva em múltiplas etapas sem proteção concorrente.

**Consequências:** Integridade deve ser garantida inclusive sob concorrência. Persistir instantes e fuso da unidade; detalhes das restrições de banco serão definidos no desenho técnico. Reagendamento e cancelamento pela IA ficam fora do MVP.

**Rastreabilidade:** SC-005 a SC-007, SC-011.

## ADR-005 — WhatsApp e processamento confiável

> Volta a valer para a Fase 1 pela ADR-024 em 06/11/2026, detalhada pela ADR-027.

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

**Consequências:** O provisionador é uma autoridade privilegiada sobre todas as clínicas e deve ser tratado como operação administrativa. A persistência JPA de clínica foi adiada até existir a primeira entidade de clínica: um segundo EntityManagerFactory desliga a autoconfiguração JPA central, e sem entidade não há o que validar. Entregue na CRM-015 (ADR-030). O cadastro público ainda não cria tarefas. Detalhes em ../provisionamento-clinicas.md.

## ADR-019 — Planejamento do produto e escopo do MVP

> Substituída pela ADR-024 em 06/11/2026. O texto abaixo permanece como histórico; o arquivo citado foi renomeado para ../planejamento-produto.md e reescrito.

**Data:** 05/11/2026. **Status:** Aceita por instrução do usuário. **Substitui:** o escopo de ../escopo.md e a sequência de entregas de ../entregas-e-pendencias.md onde houver conflito.

**Contexto:** O usuário forneceu um planejamento de produto, um backlog executável e instruções para agentes, pedindo que o planejamento passe a ser seguido e adaptado ao que já existe.

**Decisão:** ../planejamento-crm-clinicas-estetica.md é a fonte de escopo do MVP, e ../../TASKS.md é o backlog com o estado verificado. O MVP é CRM comercial: contatos, funil e oportunidades, tarefas, agenda comercial, dashboard, relatórios e administração mínima. WhatsApp, automações e campanhas passam para a Fase 2 e a IA para a Fase 3; as ADR-005, ADR-006 e ADR-007 ficam como desenho prévio dessas fases. Prevalecem as decisões técnicas já aceitas: Java/Spring em vez de NestJS, sessão de servidor em vez de Supabase Auth, banco por clínica em vez de `clinic_id` em banco compartilhado. A execução passa a seguir ../../AGENTS.md, com papéis multiagente em ../agents/ e `.claude/agents/`.

**Consequências:** O modelo comercial da ADR-008 (franquia de IA) fica sem objeto no MVP; a precificação por clínica continua pendente. Os papéis do planejamento (Proprietário, Gerente, Recepcionista, Vendedor, Profissional) diferem do catálogo atual (`ADMIN`, `RECEPTION`, `PROFESSIONAL`) e exigem decisão e migração em CRM-012. Os documentos antigos ganham aviso de substituição e permanecem como histórico.

## ADR-020 — Catálogo de papéis da clínica

> Substituída pela ADR-025 em 06/11/2026 quanto ao catálogo. Continuam valendo: um papel por participação, o invariante de último `OWNER`, os triggers da V6 e o Super Admin fora das participações. O código segue com os cinco papéis até a migração de CRM-017.

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

> **Revisão de 06/11/2026 (ADR-024, ADR-027):**
> - **Região:** a VM e o armazenamento de objetos ficam em São Paulo (`sa-east-1`), disponível no Lightsail desde junho de 2026, porque o cliente pede servidores no Brasil.
> - **Webhook:** a rota do WhatsApp é chamada pela Meta direto em `api.<domínio>`, sem passar pela Vercel. O Nginx expõe só essa rota sem sessão; o restante da API continua acessível apenas pelo rewrite.
> - **VM:** o worker de mensagens é mais uma JVM. Começar com 4 GB e medir.

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

## ADR-024 — Escopo pelo documento de requisitos do cliente

**Data:** 06/11/2026. **Status:** Aceita pelo usuário. **Substitui:** ADR-019. **Suspende:** ADR-004.

**Contexto:** O primeiro cliente, uma assessoria de marketing para clínicas, entregou o documento de requisitos v1.1 (../requisitos/requisitos-plataforma-clinicas-v1.1.pdf). O documento põe o WhatsApp oficial e a captura de origem do tráfego no centro do MVP.

**Decisão:** O documento v1.1 é a fonte de requisitos, e ../planejamento-produto.md registra como cada requisito é atendido. As fases são as do cliente:
- **F1 (MVP):**
  - contatos, funil com temperatura e vendas, origem do lead;
  - WhatsApp oficial com caixa de entrada;
  - notificações, lista exportável, dashboard da recepção, relatório mensal;
  - multi-clínica com permissões e 2FA para administradores.
- **F2:** IA de atendimento, automações e disparos, e os demais itens marcados como F2.
- **Sugestões:** só com pedido explícito.

Também ficam decididos:
- **Agenda própria:** sai do MVP.
- **Funil padrão:** passa a ser o do cliente.
- **Métricas de anúncios:** ficam fora do produto.
- **Arquitetura:** prevalecem as decisões técnicas aceitas: banco por clínica em vez de `clinic_id`, Java/Spring e sessão de servidor.

**Alternativas:**
- Manter o planejamento de 05/11: não atende o primeiro cliente.
- Adotar o documento inteiro, inclusive as sugestões de arquitetura: a coluna `clinic_id` enfraqueceria o isolamento já provado.

**Consequências:**
- **Backlog:** o `TASKS.md` é reorganizado; a agenda (CRM-041) sai do MVP.
- **Desenhos prévios:** as ADR-005 e ADR-007 voltam a ser trabalho próximo (a ADR-007 na F2).
- **Estimativa:** o MVP fica maior e a estimativa é refeita depois da prova do WhatsApp.
- **Dados de saúde:** odontologia e saúde ampliam esses dados, e a revisão de LGPD vem antes do piloto.

## ADR-025 — Catálogo de papéis do cliente

**Data:** 06/11/2026. **Status:** Aceita pelo usuário; implementação em CRM-017. **Substitui:** o catálogo da ADR-020.

**Decisão:** Os papéis de clínica passam a ser os do cliente (ADM-02):

| Papel do cliente | Papel no banco | O que é |
|---|---|---|
| Gestor | `OWNER` | Dono: plano, exportação, auditoria; toda clínica ativa tem ao menos um |
| Gestor | `MANAGER` | Operação comercial e equipe, sem mexer em `OWNER` |
| Atendente | `ATTENDANT` | Junta recepção e vendas: atende, move leads e registra vendas e perdas |
| Somente leitura | `VIEWER` | Consulta sem alterar |

**Migração:** uma nova versão do banco central converte os papéis antigos:
- `RECEPTION` e `SALES` → `ATTENDANT`;
- `PROFESSIONAL` → `VIEWER`;
- `OWNER` e `MANAGER` continuam.

O "profissional" da venda é cadastro da clínica, sem login. Admin da agência e Super Admin não são papéis de clínica. A matriz está em ../permissoes.md.

**Alternativas:**
- Manter os cinco papéis: o atendente do cliente também registra vendas, e "Somente leitura" não existe no catálogo atual.
- Permissões configuráveis por clínica: complexidade sem demanda.

**Consequências:**
- O escopo "próprios" deixa de existir no MVP: a caixa de entrada é compartilhada, e o atendente vê todos os leads da clínica.
- As matrizes e os testes de `ClinicRole` mudam junto com a migração.
- A V6 e os triggers de último proprietário continuam válidos.

## ADR-026 — Agências parceiras sobre clínicas isoladas

**Data:** 06/11/2026. **Status:** Aceita pelo usuário; desenho detalhado na ADR-028 e implementação em CRM-018.

**Contexto:** O produto é vendido de dois modos: direto a clínicas e por agências de marketing que revendem a uma carteira de clínicas.

**Decisão:** Três níveis de autorização, nunca misturados:
- **Plataforma (Super Admin):** autorização própria, fora das participações.
- **Agência:**
  - `agencies` e `agency_memberships` no banco central; `tenants.agency_id` é opcional, então uma clínica tem no máximo uma agência;
  - o Admin da agência cria clínicas na carteira (pelo provisionador), convida o primeiro gestor, conecta o WhatsApp e vê o estado das clínicas;
  - não há leitura de dados da clínica pelo papel de agência.
- **Clínica:** participações e papéis da ADR-025. Usuários da agência só leem dados de pacientes com participação explícita na clínica, que é visível ao gestor, auditada e revogável.

**Regras de segurança:**
- **Origem da agência:** vem da participação do usuário autenticado na agência. A clínica-alvo é conferida contra `tenants.agency_id` no servidor, com a mesma resposta para "não existe" e "outra agência".
- **2FA:** obrigatório para Admin da agência.
- **Painel consolidado (F2):** só agregados sem dados pessoais, calculados por clínica pelo caminho de conexão autorizado.

**Alternativas:**
- Agência como `OWNER` em todas as clínicas: dá à agência acesso total a dados de saúde, sem controle da clínica.
- Agência como tenant que contém as clínicas: quebraria o isolamento por banco.

**Consequências:**
- Novas tabelas centrais e um módulo `agency`.
- O provisionador recebe a agência de origem.
- Ficam pendentes: suspensão de agência e saída de clínica de uma agência. Também fica pendente se a agência recebe participação ao criar a clínica (proposta: `MANAGER`, revogável).

## ADR-027 — WhatsApp oficial, fila de entrada e captura de origem

**Data:** 06/11/2026. **Status:** Aceita como direção; detalhes em CRM-080, depois da prova CRM-005. **Detalha:** ADR-005.

**Decisão:**
- **Canal:** só API oficial da WhatsApp Business Platform. O fornecedor (Cloud API direta ou provedor parceiro) é escolhido depois da prova CRM-005, que precisa mostrar o `referral` de um anúncio de teste chegando ao webhook.
- **Entrada:**
  - `POST /v1/webhooks/whatsapp` público, fora da sessão e do CSRF, com assinatura `X-Hub-Signature-256` conferida sobre o corpo bruto;
  - a clínica é resolvida pelo `phone_number_id` numa tabela central de canais;
  - o evento é gravado no banco da clínica com unicidade pelo `wamid` antes de responder 200.
- **Processamento:**
  - um worker separado da API consome a fila persistente no PostgreSQL;
  - o aviso para acordar o worker não carrega dados pessoais (por exemplo, `NOTIFY` com o identificador da clínica);
  - há tentativas e registro de falha.
- **Origem:**
  - `referral` → Tráfego pago, com anúncio e `ctwa_clid`;
  - código `[CODIGO]` → origem do código;
  - senão → Não identificada;
  - a primeira origem do lead não é sobrescrita automaticamente; contatos seguintes viram eventos.
- **Tokens do canal:** são segredos; nunca ficam em código, logs ou respostas. A API pode lê-los para enviar. A escrita segue um fluxo dedicado a definir em CRM-080, sem dar ao processo HTTP o gravador genérico de segredos das clínicas (regra do ArchUnit).
- **Mídias e exportações:** armazenamento de objetos compatível com S3 na mesma região, com prefixo por clínica e links temporários.

**Alternativas:**
- API não oficial: risco de banimento e sem dados de origem.
- Processar dentro da requisição do webhook: perde mensagens em falhas e excede o tempo de resposta.
- Redis ou broker agora: sem necessidade medida.

**Consequências:**
- Novo processo na VM e nova superfície pública, que exige revisão de `security`.
- Custo por template, inclusive nas notificações ao gestor.
- A atribuição de origem é parcial e não pode ser prometida como completa.

## ADR-028 — Convites, clínica aguardando o gestor e API com menos permissões

**Data:** 07/11/2026. **Status:** Aceita pelo usuário, depois da revisão do `architect`. Implementação em três PRs da CRM-018, com os convites da CRM-013. **Detalha:** ADR-026.

**Contexto:** A agência cria a clínica antes de o gestor ter conta, e o banco exige um `OWNER` para ativar uma clínica (V6). A V4 dava à API `INSERT`, `UPDATE` e `DELETE` em `tenants` e `user_accounts`, sem uso, e criar clínicas pela API passaria a depender disso.

**Decisão:**
- **Clínica aguardando o gestor:**
  - a tarefa de provisionamento pode não ter administrador; nesse caso, o provisionador deixa a clínica em `AWAITING_OWNER`;
  - o gestor entra por convite e, ao aceitar, vira `OWNER` e a clínica é ativada;
  - se ele aceitar enquanto a clínica ainda está em provisionamento, o provisionador a ativa direto;
  - os dois lados travam a mesma linha da clínica.
- **A agência não ganha participação** nas clínicas que cria. Ela só vê dados de pacientes se o gestor a convidar.
- **Risco aceito:** a agência pode indicar um e-mail próprio como gestor. As travas:
  - a agência só emite o convite enquanto a clínica não tem `OWNER`;
  - há no máximo um convite pendente vindo da agência por clínica;
  - depois do aceite, a agência não emite nenhum convite;
  - o gestor vê toda a equipe.
- **Admins de agência** são criados só pelo comando de plataforma, que roda sem HTTP e com credencial própria. O Admin da agência não convida outros admins nesta entrega.
- **Convites:**
  - token aleatório guardado só como hash, com validade e uso único;
  - o token vai no fragmento da URL e no corpo da requisição, nunca no caminho;
  - conta verificada exige login da própria conta para aceitar, e nunca tem a senha trocada;
  - conta inativa é recusada.
- **Permissões da API (V8):**
  - cria clínicas só pelas colunas permitidas, sem `status`, então toda clínica nasce `PROVISIONING`; essa permissão entra com o serviço da agência (PR 3), não antes;
  - não muda o status nem a agência de uma clínica, e a única ativação possível é `activate_awaiting_clinic` (`AWAITING_OWNER` → `ACTIVE`, ainda exigindo `OWNER`);
  - não grava contas nem participações direto; essas permissões voltam coluna por coluna com o fluxo de convites;
  - mantém `UPDATE` só em `tenants.updated_at`, porque travar linhas exige permissão de escrita em ao menos uma coluna.
- **Transições de estado** permitidas, com agência imutável (trigger da V8):
  - `PROVISIONING` → `AWAITING_OWNER`, `ACTIVE` ou `FAILED`;
  - `FAILED` → `PROVISIONING`;
  - `AWAITING_OWNER` → `ACTIVE` ou `SUSPENDED`;
  - `ACTIVE` → `SUSPENDED`;
  - `SUSPENDED` → `ACTIVE`. Nunca `SUSPENDED` → `AWAITING_OWNER`, que deixaria `activate_awaiting_clinic` tirar uma suspensão.
- **Detalhes do convite (PR 2):**
  - validade de 7 dias; reenviar gira o token;
  - senha de 12 caracteres a 72 bytes (limite do bcrypt);
  - sem login automático depois do aceite;
  - conta não verificada é tomada pelo token, que prova o e-mail;
  - e-mail enviado depois do commit, com o link só de `APP_PUBLIC_ORIGIN`;
  - convite de equipe só em clínica `ACTIVE`; convite de gestor da agência só em `PROVISIONING` ou `AWAITING_OWNER` sem nenhuma participação `OWNER`, ativa ou não, e nunca aceito por um admin ativo da própria agência;
  - a API não altera contas: a tomada de conta não verificada é a função `claim_unverified_account` (V10);
  - limites de envio por convite e por clínica.
- **Carteira (PR 3):**
  - criação de clínica idempotente por `requestId` dentro da agência;
  - no máximo 20 clínicas em preparação por agência (`app.agency.max-unfinished-clinics`);
  - reemissão do convite do gestor só enquanto a clínica nunca teve dono;
  - a carteira mostra só dados do registro, nunca o e-mail do gestor;
  - o comando de plataforma usa a credencial `crm_platform_operator`, imprime o link uma vez (sem o e-mail) e não envia e-mail; o serviço do Compose não guarda log (`logging: none`);
  - o operador não tem nenhum privilégio direto em convites: emite convite de admin de agência só pela função `platform_issue_agency_admin_invitation` (V12), com tipo AGENCY fixo e agência ativa;
  - a API nunca cria convite AGENCY (trigger da V12), e o aceite recusa convite AGENCY com emissor;
  - o aceite trava o convite pelo ID e pelo hash do token, então um link girado nunca vence uma corrida;
  - reemissão do convite do gestor: até 5 e-mails por clínica por dia, independente do endereço, 1 por minuto e 200 por agência por dia;
  - o limite de clínicas em preparação é serializado por agência (`pg_advisory_xact_lock`).
- **Limites conhecidos:**
  - as checagens de agência suspensa e de emissor ativo não travam as linhas de `agencies` e `agency_memberships`, então uma suspensão concorrente pode ser confirmada logo depois de um aceite;
  - o preview não reconfere a autoridade do emissor; quem tem o token vê o nome da clínica, mas o aceite falha;
  - quando existir a desativação de admin de agência, ela precisa revogar os convites AGENCY pendentes daquele e-mail; senão, um link antigo reativa o acesso.
- **Contrato do aceite (PR 2):**
  - o aceite trava a linha da clínica (`FOR NO KEY UPDATE`) antes de inserir o `OWNER`, porque a FK da participação não conflita com a trava do provisionador;
  - aceita só clínica `PROVISIONING` ou `AWAITING_OWNER`;
  - cria a participação e confere a conta na mesma transação;
  - chama `activate_awaiting_clinic` sempre que o status travado for `AWAITING_OWNER`;
  - a autorização (quem convidou, de qual agência) é conferida no serviço, porque a função não sabe quem a chama.

**Alternativas:**
- A agência como dona provisória: daria acesso a dados de saúde sem decisão da clínica.
- Ativar sem dono: quebra o invariante da V6.
- Exigir aprovação da plataforma para cada convite de gestor: mais seguro, mas faz cada clínica nova depender da equipe Branly. Foi recusada pelo responsável.

**Consequências:**
- Admin da agência e `OWNER` existem sem segundo fator até a CRM-016, então não pode haver dados reais nem produção antes dela.
- Ainda não há auditoria central dos convites e do comando de plataforma (CRM-070).
- Ficam pendentes: retenção dos e-mails em convites, o que significa uma agência suspensa e como uma clínica sai de uma agência.

## ADR-029 — Segundo fator TOTP para administradores

**Data:** 08/11/2026. **Status:** Aceita pelo usuário, depois da revisão do `architect`. Implementação em CRM-016.

**Decisão:**
- **Quem precisa:** obrigatório, sem carência, para participação `OWNER` ativa em qualquer clínica e para admin ativo de agência. Opcional para os demais. O Super Admin entra na mesma regra na CRM-060.
- **Estado derivado a cada requisição** (`MfaGateFilter`), nunca guardado como marca:
  - prova na sessão presa a `subject_id` e `auth_version`, removida a cada login;
  - lista de permitidos por estado;
  - login inalterado (204) e estado em `GET /v1/auth/mfa`.
- **TOTP** (RFC 6238, SHA-1, 6 dígitos, 30 s, janela de ±1):
  - feito só com o JDK, sem dependência nova;
  - o segredo é cifrado com AES-256-GCM, com chave derivada por HKDF de `MFA_ENCRYPTION_KEY` (só na API) e AAD com propósito, sujeito e chave;
  - os códigos de recuperação ficam como HMAC, com cerca de 80 bits cada.
- **Banco** (V13): tabelas no banco central, com escrita só por funções `SECURITY DEFINER`. Repetição barrada por `last_used_step`, contador persistente de falhas com bloqueio e revogação de sessões por `auth_version`.
- **Cadastro só com a sessão de senha, com aviso por e-mail** (risco aceito pelo responsável): quem tem só a senha de um administrador que ainda não cadastrou o fator pode cadastrar o próprio celular.
- **Recuperação de quem perdeu tudo:** o comando de plataforma `reset-mfa`, pela função `platform_reset_mfa`, depois de confirmar a identidade por fora.

**Alternativas:**
- Confirmar o primeiro cadastro por link no e-mail (mais seguro, mais trabalho agora).
- Mudar o login para 200 com corpo (quebraria o contrato do frontend).
- Fatores de autoridade do Spring Security (a exigência é dinâmica e o projeto não usa autoridades globais na sessão).

**Consequências:**
- A API não sobe sem `MFA_ENCRYPTION_KEY`. Perder essa chave invalida todos os fatores cadastrados (rotação e guarda em CRM-072).
- O reset pela plataforma grava um aviso pendente, enviado pela API no próximo login (V14); quem não entra de novo não recebe.
- Bloqueio progressivo por conta, de 15 minutos até 24 horas, com aviso por e-mail. As tentativas são reservadas antes da comparação.
- Ainda não há auditoria central (CRM-070).

## ADR-030 — Persistência JPA da clínica

**Data:** 09/11/2026. **Status:** Aceita pelo usuário, depois da revisão do `architect`. Implementação em CRM-015. **Complementa:** ADR-014 e ADR-018.

**Decisão:**
- **Unidade própria:** `ClinicPersistence` monta um `SessionFactory` do Hibernate por bootstrap nativo e o guarda como campo privado. Ele nunca vira bean: um bean desse tipo é um `EntityManagerFactory` e desligaria a JPA central do Spring Boot.
- **Tenant explícito:** cada sessão é aberta com `withOptions().tenantIdentifier(ClinicTenant)`, montado só a partir de `ClinicAccess`. Não há `CurrentTenantIdentifierResolver` nem ThreadLocal; sessão sem tenant é recusada pelo Hibernate.
- **Conexões:** o `MultiTenantConnectionProvider` só chama `TenantConnectionRepository.open(tenant, sujeito)`. Participação ativa, clínica `ACTIVE`, segredo e `tenant_identity` são conferidos a cada transação. `getAnyConnection` falha, e o boot não lê metadados JDBC (dialeto e versão fixos).
- **Transação no repositório:** `inTransaction(tenant, callback)` faz uma transação por chamada, com rollback em qualquer exceção. O callback devolve records de domínio. Services continuam sem JPA.
- **Registro de entidades por módulo:** cada módulo mantém as entidades no próprio pacote `persistence` e declara um bean `ClinicEntityRegistration` com a lista explícita, sem varredura e sem ciclo entre módulos.
- **JPA central vazia:** `ControlJpaConfiguration` declara `PersistenceManagedTypes` vazio, substituindo a varredura de pacotes do Spring Boot.
- **Sem caches compartilhados:** cache de segundo nível, cache de consultas e estatísticas ficam desligados.
- **Erros:** recusas de acesso continuam `TenantAccessException`; falhas de banco viram `ClinicDataException` com código estável (`SCHEMA_OUTDATED`, `INVALID_DATA`, `IDENTITY_MISMATCH`, `UNAVAILABLE`). O log do Hibernate com a mensagem do PostgreSQL fica desligado; a unidade registra só código, SQLState e clínica, e o banco que declara outra clínica sai como incidente de segurança.
- **Limites:** `inTransaction` aninhado falha na hora (uma flag por thread, sem tenant). As conexões de clínica usam `statement_timeout` de 10 s e `lock_timeout` de 3 s, para que uma transação travada não segure uma das poucas conexões do pool.
- **ArchUnit:** `@Entity` só em pacotes `persistence`, sem `@Cache`/`@Cacheable`; JPA e Hibernate só em `persistence` e `repositories`; identity, provisioning, platform, config, agency e onboarding não alcançam a unidade nem transitivamente; `ClinicTenant` só por `of(ClinicAccess)` e `ClinicAccess` só pelo registro e pelo `TeamService`.
- **Primeira entidade:** `clinic_settings` (fuso IANA, linha única), migração de clínica V3, com `SELECT` e `UPDATE (time_zone)` para o runtime.

**Alternativas:**
- Segundo `EntityManagerFactory` gerenciado pelo Spring (desliga a autoconfiguração central).
- Resolver de tenant por ThreadLocal (vazamento entre requisições e workers).
- JDBC puro também no banco da clínica (perde o mapeamento sem ganho de isolamento).

**Consequências:**
- Banco central e banco da clínica não compartilham transação; nenhum caso de uso pode depender de commit conjunto.
- `inTransaction` não pode ser aninhado: o pool de cada clínica tem poucas conexões.
- Conflitos de integridade (único, check) ainda saem como `UNAVAILABLE`; o motivo próprio entra com a primeira tabela que precisar dele (CRM-020).
- O callback recebe a `Session` inteira: comandos de sessão (`SET`, `doWork`) persistem na conexão do pool da mesma clínica. A revisão de código barra esse uso.
- O schema da clínica não é validado no boot, porque não há tenant; o `ClinicPersistenceIT` valida as entidades contra uma clínica provisionada, como runtime.
- Migrações novas de clínica só chegam a clínicas já existentes quando houver o comando de rollout (pendência). Até lá, tabela ausente falha fechada com `CLINIC_DATA_SCHEMA_OUTDATED`.
