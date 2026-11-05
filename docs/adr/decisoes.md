# Registros de decisões arquiteturais

## ADR-010 — MVC REST com contrato documentado na interface

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
