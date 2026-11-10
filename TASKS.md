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

### [x] CRM-017 — Migrar para os papéis do cliente (P0)
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
**Estado (06/11/2026):** concluída na branch `feat/crm-017-client-roles`.
- **Banco:** `V7__client_roles.sql` troca a constraint e converte os papéis antigos.
- **Código:** `ClinicRole` com `OWNER`, `MANAGER`, `ATTENDANT` e `VIEWER`. `ClinicPermission` segue a matriz vigente de [permissoes.md](docs/permissoes.md). O `MANAGER` gerencia só `ATTENDANT` e `VIEWER`.
- **Testes:** `./mvnw -B -ntp clean verify` → 102 unitários (2 ignorados no Windows) e 33 de integração passaram. Entre eles:
  - `ClinicRoleTest`: matriz linha a linha;
  - `ClientRolesMigrationIT`: V6 com papéis antigos → V7, nomes antigos recusados, trigger de último proprietário ativo;
  - `TeamManagementIT`: Atendente e Somente leitura recebem 403 ao mudar papel ou desativar alguém, e nomes antigos recebem 400;
  - `TeamRuntimeGrantsIT` e `ApiConventionsIT`.
- **Revisão de `security`:** sem achados altos.
  - Acesso: ex-`PROFESSIONAL` passa a ler a clínica toda como `VIEWER`. Não há dados reais em nenhum ambiente, então ninguém foi afetado. Registrado em permissoes.md.
  - Deploy: aplicar a V7 antes da API nova e sem versões misturadas. Também registrado.
  - Correções feitas: documentação desatualizada e teste HTTP de recusa.

### [x] CRM-018 — Agências parceiras e carteira de clínicas (P0)
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
**Estado (07/11/2026):** em andamento. Desenho revisado pelo `architect` e registrado na ADR-028 (convites da CRM-013 entram junto; agência sem participação; admins de agência só pela plataforma). Dividida em três PRs:
1. **Banco e provisionamento** (`feat/crm-018-agency-schema`): V8 com agências, `AWAITING_OWNER`, transições e permissões reduzidas da API; provisionador sem administrador. **Pronto para PR:**
   - `./mvnw -B -ntp clean verify` → 102 unitários (2 ignorados no Windows) e 44 de integração. Entre eles, `ControlRuntimeGrantsIT`, que roda como a API, e os casos novos de `TenantIsolationIT`: clínica sem admin, dono aceito durante o provisionamento e aceite concorrente segurando a trava.
   - **Compose:** V7 e V8 aplicadas sobre a V6, `verify-roles.sh` passou, a função de ativação pertence ao migrador e ativou uma clínica sintética como runtime, em transação desfeita.
   - **Revisão de `security`:** sem achados altos. Corrigidos: transição `SUSPENDED` → `AWAITING_OWNER` removida, permissão de criar clínicas adiada para o PR 3, teste de concorrência real e checagens exatas de colunas no script.
   - **Requisitos para o PR 2** (ADR-028, "Contrato do aceite"):
     - o aceite trava a clínica antes de inserir o `OWNER`;
     - o código BRL02 recebe um erro estável na API.
2. **E-mail, convites e aceite**, inclusive convites de equipe (`feat/crm-018-invitations`). **Pronto para PR:**
   - V9 com convites e grants por coluna;
   - adaptador de e-mail local e Resend;
   - módulo `onboarding` com aceite (travas clínica → convite → participação), convites de equipe e rotas públicas com CSRF e limite.
   - **Testes:** `./mvnw -B -ntp clean verify` → 121 unitários (2 ignorados no Windows) e 57 de integração; `TeamInvitationIT` e `InvitationAcceptanceIT` rodam como a API.
   - **Compose:** V9 e V10 aplicadas, `verify-roles.sh` passou, provisionador sobe sem os beans web e as rotas públicas respondem pelo Nginx.
   - **Revisão de `security`:** sem achados altos. Corrigidos:
     - convite de agência limitado a clínica sem dono e em provisionamento;
     - API sem `UPDATE` em contas (V10);
     - limites de envio;
     - relógio atual no aceite;
     - falha de ativação não confirma o aceite;
     - erro de serialização vira `FAILED`;
     - admin da agência não aceita o próprio convite;
     - checagens exatas no script.
   - **Pendente:**
     - preview sem reconferir a autoridade (só quem tem o token vê o nome da clínica);
     - janela sem trava na suspensão de agência;
     - teste de `delivery: FAILED`.
3. **Módulo `agency`** (carteira e criação de clínica) e comando de plataforma (`feat/crm-018-agency-portfolio`). **Pronto para PR:**
   - **Banco:** V11 com grants da carteira e papel `crm_platform_operator`; V12 restringe o operador a uma função e impede a API de criar convites de agência.
   - **Agência:** módulo `agency` com agências do admin e carteira só com dados do registro.
   - **Carteira:** criação idempotente de clínica com tarefa sem admin e convite do gestor; reemissão só antes de haver dono, com limites por clínica e por agência.
   - **Aceite:** convite de admin de agência.
   - **Plataforma:** comando `create-agency` e `invite-agency-admin`.
   - **Erros:** BRL02 vira `STATE_CONFLICT`.
   - **Testes:** `./mvnw -B -ntp clean verify` → 123 unitários (2 ignorados no Windows) e 67 de integração. O `AgencyPortfolioIT` faz o fluxo completo da plataforma ao gestor, com a API como runtime e o comando como operador.
   - **Compose:** V11 e V12 aplicadas, `verify-roles.sh` passou e o comando de plataforma rodou (link na saída, sem e-mail e sem log guardado).
   - **Revisão de `security`:** o achado alto foi corrigido (operador podia ler e girar convites de clínica). Médios corrigidos: reemissão por endereço e link no log. Baixos corrigidos: limite serializado, trava pelo hash do token, convite AGENCY só da plataforma, replay com status real, nome sem caracteres de controle, parser estrito e reemissão sem `FAILED`.

**Concluída (07/11/2026)** com os três PRs. Aceite cumprido:
- isolamento entre agências e clínicas diretas, com a mesma resposta;
- usuário só de agência não lê dados de clínica (sem participação, 403 ao selecionar);
- criação idempotente sob repetição e concorrência;
- revisões de `architect` e `security`.

**Pendências:**
- 2FA de Admin da agência e de `OWNER` (CRM-016) antes de dados reais;
- auditoria (CRM-070);
- suspensão de agência e saída de clínica;
- revogar convites pendentes ao desativar admin de agência;
- telas (FE).

**Bloqueio para dados reais:** Admin da agência e `OWNER` sem 2FA até a CRM-016.

### [x] CRM-016 — Segundo fator (TOTP) para administradores (P0)
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
**Estado (08/11/2026):** concluída na branch `feat/crm-016-totp` (ADR-029). Implementada pelo agente `backend`, com desenho revisado pelo `architect`.
- **Portão:** `MfaGateFilter` com estado derivado a cada requisição e lista de permitidos. O login continua 204, e o estado sai de `GET /v1/auth/mfa`.
- **Obrigatoriedade:** para `OWNER` ativo e admin de agência, sem carência; opcional para os demais.
- **Fator e recuperação:** TOTP feito só com o JDK, segredo em AES-GCM com chave da API, 10 códigos de recuperação em HMAC e avisos por e-mail.
- **Banco:**
  - V13 com funções `SECURITY DEFINER` e sem escrita direta;
  - V14 com reserva de tentativas, bloqueio progressivo até 24 h e avisos pendentes do reset.
- **Recuperação:** `reset-mfa` no comando de plataforma.
- **Testes:** `./mvnw -B -ntp clean verify` → 139 unitários (2 ignorados no Windows) e 87 de integração. Cobrem:
  - varredura de todas as rotas nos estados pendente e de cadastro;
  - reaproveitamento de sessão e fixação;
  - repetição e concorrência;
  - recuperação de uso único;
  - bloqueios;
  - troca e desativação;
  - grants como runtime e operador.
- **Revisão de `security`:** sem críticos nem altos. Médios corrigidos: bloqueio progressivo com aviso; aviso do reset. Baixos corrigidos: tentativa reservada antes de comparar, aviso de novos códigos, varredura no estado de cadastro, `no-store` e ordem do filtro.
- **Pendências:**
  - Super Admin (CRM-060);
  - auditoria (CRM-070);
  - guarda e rotação de `MFA_ENCRYPTION_KEY` (CRM-072);
  - aviso do reset só no próximo login;
  - telas de cadastro e verificação do segundo fator no frontend (ainda sem tarefa FE).

### [-] CRM-013 — Convites, onboarding e configurações da clínica (P1)
**Depende de:** CRM-012, CRM-018.
**Atende:** ADM-01, ADM-02; base para ADM-06 (F2).
**Entregas:**
- **Convite:** por e-mail com validade, uso único e token guardado como hash; aceite e reativação de membros.
- **Configurações da clínica:** nome e fuso.
- **Telas:** login, aceite de convite, seleção de clínica, carteira da agência e acompanhamento do provisionamento.
**Aceite:** clínica nova entra no sistema sem enxergar registros de outras; convite vencido, reutilizado ou de outra clínica é recusado; perfis têm acessos adequados.
**Estado:** o provisionador cria banco, papéis e segredo e ativa a clínica com o `OWNER` (ADR-018), ou a deixa `AWAITING_OWNER` (ADR-028). Convites, aceite e reativação de membros entram no PR 2 da CRM-018. **Falta:** nome da clínica, a rota do fuso (o armazenamento entrou na CRM-015, `ClinicSettingsRepository`) e as telas.

### [ ] CRM-014 — Cadastro público de clínica direta (P2)
**Depende de:** CRM-013, CRM-016.
**Entregas:** pedido de cadastro idempotente, verificação de e-mail com token guardado só como hash, reenvio limitado e tarefa de provisionamento na mesma transação ([plano-onboarding.md](docs/plano-onboarding.md), etapas 2–3).
**Aceite:**
- Cadastro repetido ou concorrente não duplica identidade, clínica ou tarefa.
- Token vencido, reutilizado ou incorreto é recusado.
- Respostas não revelam se o e-mail existe.
- Não é possível tomar conta existente.
**Observação:** necessário só para abrir venda direta em autosserviço. No piloto, clínicas diretas são criadas pela plataforma.

### [x] CRM-015 — Persistência JPA da clínica (P0)
**Depende de:** CRM-012.
**Entregas:** unidade de persistência da clínica sobre `TenantConnectionPools`, com tenant explícito (sem ThreadLocal), sem desligar a JPA central; remoção da regra ArchUnit que proíbe `@Entity` (ADR-018).
**Aceite:** entidade de clínica só lê e grava no banco da clínica autorizada; teste A/B com a primeira entidade; nenhuma entidade no EntityManager central.
**Estado (09/11/2026):** concluída na branch `feat/crm-015-tenant-jpa` (ADR-030). Desenho revisado pelo `architect` antes.
- **Unidade:** `ClinicPersistence` com `SessionFactory` privado (não é bean), tenant por objeto `ClinicTenant` vindo de `ClinicAccess`, sem resolver, e conexões só por `TenantConnectionRepository.open`. Cada módulo registra as entidades com `ClinicEntityRegistration`.
- **JPA central:** `PersistenceManagedTypes` vazio; metamodel central sem entidades.
- **Primeira entidade:** `clinic_settings` (fuso IANA), migração de clínica V3 com `SELECT` e `UPDATE (time_zone)` para o runtime, e `ClinicSettingsRepository`. A rota fica para a CRM-013.
- **Robustez:** transação aninhada falha na hora; `statement_timeout` e `lock_timeout` nas conexões de clínica; códigos estáveis (`CLINIC_DATA_*`); log SQL do Hibernate desligado; banco com identidade de outra clínica sai como incidente.
- **ArchUnit:** `@Entity` só em `persistence`, sem cache de entidade, JPA só em `persistence`/`repositories`, módulos de controle sem acesso à unidade nem transitivamente, construtores de `ClinicTenant` e `ClinicAccess` restritos.
- **Testes:** `./mvnw -B -ntp clean verify` → 170 unitários (2 ignorados no Windows) e 99 de integração. Entre eles:
  - `ClinicPersistenceIT`: A/B de leitura e escrita, sujeito de A recusado em B sem abrir pool, participação desativada, rollback, grants do runtime, schema desatualizado, dado inválido, identidade divergente, aninhamento, `lock_timeout`, metamodel central vazio e mapeamento validado como runtime;
  - `ClinicPersistenceTest`: tradução de exceções, provedor sem tenant e sessão sem tenant recusada;
  - `ClinicSettingsTest` e `ArchitectureTest`.
  - `TenantIsolationIT` passou a contar as migrações de clínica pelo classpath.
- **Revisões:** `security` sem críticos nem altos. Corrigidos os médios (aninhamento, timeout de lock e fronteira de módulos no ArchUnit) e os baixos de construtores, log SQL, identidade, CHECK do fuso, cache e rollback em `Error`. `reviewer` sem bloqueantes; sugestões aplicadas.
- **Pendências:**
  - ~~comando para aplicar migrações de clínica novas a clínicas já existentes~~: resolvido em 09/11/2026 pelo modo `tenant-migrate` (ADR-031), na branch `feat/tenant-migration-rollout`, a partir desta. Detalhes abaixo;
  - ~~motivo de conflito de integridade junto da CRM-020~~ (feito: `CONFLICT` e `STALE`);
  - `MfaLockoutIT.aParallelBurstComparesAtMostTenWrongCodes` falhou uma vez numa execução completa (todas as respostas 429) e passou nas seguintes: instabilidade anterior a esta tarefa.

**Rollout das migrações de clínica (09/11/2026, ADR-031):** complemento da CRM-015, desenho revisado pelo `architect` e aprovado pelo usuário.
- **Comando:** `APP_MODE=tenant-migrate` e serviço `tenant-migrate` no profile `tools` do Compose (`docker compose --profile tools run --rm tenant-migrate [--tenant-id UUID]`).
  - Usa as credenciais do provisionador, com os segredos só para leitura, sem gravador de segredos, fila ou scheduler.
  - Alcança clínicas `ACTIVE`, `AWAITING_OWNER` e `SUSPENDED`, uma por vez.
  - Cada clínica passa por pré-checagem só de leitura, migração como `owner_<id>` e verificação de login do runtime.
  - Falha isolada com código estável, relatório por clínica em stdout, códigos de saída 0, 1 e 2; retomada por reexecução.
- **Provisionador:** a etapa VERIFICATION migra de novo antes de verificar, e a sessão de migração usa `lock_timeout` de 10 s.
- **Testes:**
  - `TenantMigrationRolloutIT`: clínica sem a V3 e a V4 volta a funcionar por `ClinicSettingsRepository` e pelo `ContactService` das rotas de contato; falha isolada entre duas clínicas e retomada; `SUSPENDED` sim, `PROVISIONING`/`FAILED` não; sem banco recusada sem criar banco; `--tenant-id`; duas execuções simultâneas aplicam uma vez;
  - `TenantMigrationRolloutTest`: uso inválido, ordem das etapas, códigos sem texto de causa, banco central indisponível e beans separados entre os modos;
  - `TenantMigrateModeIT`: o contexto completo do modo sobe sem worker, scheduler nem MFA;
  - `TenantProvisioningServiceTest` e a regra ArchUnit `onlyTheProvisioningWorkerHoldsWorkerCapabilities`.
- **Execução (10/11/2026, sobre a `main` com CRM-020 e CRM-021):** `./mvnw -B -ntp clean verify` → 236 unitários (2 ignorados no Windows) e 131 de integração, tudo verde.
  - Em execuções anteriores, sobre a CRM-015, `MfaLockoutIT.aParallelBurstComparesAtMostTenWrongCodes` falhou três vezes (todas as respostas 429). É a instabilidade já registrada: o teste roda antes dos ITs novos e passa isolado.
- **Revisão `security`:** sem críticos nem altos. Corrigidos: ArchUnit por chamada e por pacote, Flyway sem detalhe de erro do servidor, logs verificados no IT, boot real do modo testado e ordem de deploy com o provisionador antigo parado. Riscos aceitos na ADR-031: credenciais completas do worker e do provisionador no processo (papéis próprios exigem decisão) e `lock_timeout` sem limite de duração do DDL.
- **Pendências:** runbook de produção na CRM-072; aprovação humana a cada execução em produção.

## Fase 2 — Contatos e histórico

Todas as tabelas desta fase em diante vivem no banco da clínica (`db/tenant-migration`), com `GRANT` explícito a `${runtimeRole}` em cada migração.

### [x] CRM-020 — Modelar contatos, tags e consentimento (P1)
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
**Estado (09/11/2026):** concluída na branch `feat/crm-020-contacts` (ADR-032). Desenho revisado pelo `architect`; as 12 decisões foram aprovadas pelo usuário.
- **Módulo `contact`:** domínio, entidades na persistência da clínica, `ContactRepository`, `TagRepository`, `ConsentRepository` e `ContactException`. Sem API nem RBAC (CRM-021).
- **Migração de clínica V4:** `contacts`, `tags`, `contact_tags` e `contact_consents`, com `pg_trgm`, CHECK do telefone canônico e da autoria, UNIQUE de telefone inclusive para arquivados e grants por coluna (consentimento só com inserção).
- **Telefone:** normalizador próprio, com o 9º dígito inserido em celular brasileiro antigo; a criação devolve o contato existente pelo `ON CONFLICT`, sem erro.
- **Consentimento:** o mais recente prevalece, opt-out vence empate, importação não reverte opt-out, e a ordem do histórico segue as escritas, não o relógio.
- **Persistência da clínica:** motivos `CONFLICT` e `STALE`, registrados só em DEBUG.
- **Testes:** `./mvnw -B -ntp clean verify` → 220 unitários (2 ignorados no Windows) e 113 de 114 de integração; a falha é o `MfaLockoutIT` instável (passa isolado). Entre os novos:
  - `ContactPersistenceIT` (15), com clínicas provisionadas:
    - mesmo número em formatos diferentes;
    - 8 criações concorrentes resultam num contato só;
    - arquivado mantém o número;
    - versão e duplicidade com o id do contato existente;
    - busca por nome sem acento, dígitos, número inteiro em formato antigo e e-mail, com curingas literais;
    - tags e cascata;
    - consentimento com relógio parado e recuado;
    - consentimento concorrente;
    - dado gravado fora das regras como `INVALID_DATA`;
    - isolamento A/B em leitura, lista, busca, filtro, alteração, vínculo e consentimento;
    - grants do runtime;
    - CHECKs como dono;
    - mapeamento validado como runtime.
  - `PhoneNumberTest` (38), `ContactDomainTest` (12), `ClinicPersistenceTest` e `ArchitectureTest`.
- **Revisões:**
  - `security`: sem críticos nem altos. Médios corrigidos: ordem do consentimento independente do relógio, e-mail validado com Unicode, duplicidade verificada antes do UPDATE e listagem sem observações nem nascimento.
  - `reviewer`: sem bloqueadores. Corrigidos: dado inválido gravado vira `INVALID_DATA`, arquivamento concorrente idempotente, corrida na criação, busca pelo número inteiro, caracteres invisíveis, limite do `name_search` e código morto.
- **Pendências:**
  - a V4 só chega a clínicas existentes pelo rollout `tenant-migrate` (ADR-031, branch `feat/tenant-migration-rollout`, sem merge); mergear antes da CRM-021;
  - `log_error_verbosity=terse` no PostgreSQL, para o DETAIL de erros não levar dados pessoais ao log do servidor (decisão de infraestrutura, CRM-072);
  - para a CRM-021: origem do consentimento fixada pelo canal, política de leitura antes de expor o contato existente, termo de busca de 2 caracteres sem índice;
  - para a CRM-082: o webhook prefixa `+` no `wa_id` de números estrangeiros;
  - para a CRM-073: finalidade de nascimento e observações no inventário.

### [x] CRM-021 — API de contatos (P1)
**Depende de:** CRM-020, CRM-004.
**Atende:** PAC-01, PAC-03, PAC-06, PAC-08.
**Entregas:** criar, editar, listar, detalhar, buscar, filtrar, paginar, arquivar, tags e consentimento; aviso de duplicidade antes de criar.
**Aceite:** permissões da matriz testadas; filtros e buscas não vazam entre clínicas (A/B); dados inválidos recusados com `INVALID_INPUT`.
**Estado (09/11/2026):** concluída na branch `feat/crm-021-contacts-api`. Desenho revisado pelo `architect`; as 9 decisões foram aprovadas pelo usuário.
- **Rotas** (`/v1/clinic`, clínica da sessão):
  - `GET /contacts` com `q`, `tagId`, `includeArchived` e paginação;
  - `POST /contacts/phone-check` (aviso de duplicidade antes de criar);
  - `POST /contacts` (201 com `Location`);
  - `GET` e `PUT /contacts/{id}`, com `version` no corpo;
  - `PUT /contacts/{id}/archived` (arquivar e desarquivar);
  - `GET` e `POST /contacts/{id}/consents`;
  - `PUT` e `DELETE /contacts/{id}/tags/{tagId}`;
  - `GET` e `POST /tags`, `PUT` e `DELETE /tags/{id}`.
- **Regras:**
  - validação do formato, depois a permissão, depois a leitura por id: 403 não revela se o id existe, e id de outra clínica responde 404;
  - lista e phone-check sem observações nem nascimento;
  - consentimento sempre gravado como `MANUAL`;
  - contato arquivado recusa edição e novas tags (409 `CONTACT_ARCHIVED`), mas aceita consentimento e remoção de tag;
  - 409 `CONTACT_DUPLICATE_PHONE` traz o `contactId` (esquema `ContactConflictError`);
  - teto de 500 tags por clínica (`TAG_LIMIT`).
- **Permissão nova:** `TAG_MANAGE` (Proprietário e Gestor) para renomear e excluir tags, em `ClinicRole`, `permissoes.md` e `ClinicRoleTest`.
- **Erros do banco da clínica:** `ClinicDataErrorHandler` global. Indisponível ou migração pendente → 503 com `Retry-After`; dado inválido → 500; credencial ausente → 503 e credencial inválida → 500, ambas registradas só com o motivo.
- **Contrato:** `docs/api/openapi.json` regenerado. Só ganhou rotas e esquemas; nenhuma operação existente mudou, e as novas têm `operationId` explícito. O customizador do OpenAPI preserva esquemas de erro próprios (`*Error`).
- **Testes:** `./mvnw -B -ntp clean verify` → 225 unitários (2 ignorados no Windows) e 125 de integração, BUILD SUCCESS. Entre os novos:
  - `ContactApiIT` (9, HTTP com cookies, CSRF e segundo fator reais):
    - criação, detalhe, lista e aviso de duplicidade;
    - matriz de papéis rota por rota, com o código `CLINIC_ACCESS_DENIED`;
    - edição, versão, arquivamento e desarquivamento;
    - consentimento forçado a `MANUAL`;
    - isolamento A/B em todas as rotas, na busca, no filtro e no phone-check;
    - `INVALID_INPUT` em 16 campos, sem eco do valor;
    - catálogo de tags;
    - paginação;
    - sessão sem clínica, sem CSRF e sem segundo fator.
  - `ContactServiceTest` (5): validação antes da autorização, papel recusado sem tocar o banco da clínica, origem `MANUAL` e `TAG_MANAGE`.
  - `ContactPersistenceIT` (17): teto de tags e contato arquivado.
- **Revisões:**
  - `security`: sem críticos nem altos. Corrigidos: log da credencial da clínica, `toString` mascarado nos DTOs, `Location` sem query string e teto de tags.
  - `reviewer`: sem bloqueadores. Corrigidos: esquema do 409 no contrato, teste dependente da ordem, `operationId` explícitos, paginação testada, código do 403 conferido, consultas do detalhe reaproveitadas e ordem das chaves de consentimento.
- **Pendências:**
  - **CRM-072:**
    - `log_format` do Nginx sem a query string em `/api/`, porque o termo `q` pode trazer nome ou telefone;
    - `proxy_set_header X-Forwarded-Host $host`;
    - conferir os logs da Vercel.
  - **CRM-073:**
    - finalidade das observações e do nascimento, e se o `VIEWER` deve vê-los;
    - se a reversão de opt-out exige evidência ou motivo.
  - **Auditoria (CRM-070):** leitura e extração em massa de contatos não são auditadas nem limitadas por taxa.
  - **Rollout:** o `tenant-migrate` ainda não está na `main`; no Compose local, clínicas criadas antes da V4 respondem 503.
  - **Riscos aceitos:**
    - renomear tag não tem versão (a última escrita vence);
    - vincular tag e arquivar ao mesmo tempo pode deixar uma tag num contato arquivado (só estética).

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

### [x] CRM-030 — Modelar funis e etapas configuráveis (P1)
**Depende de:** CRM-015.
**Atende:** CRM-01.
**Entregas:** `pipelines` e `pipeline_stages` com ordem e tipo (aberta, ganha, perdida); funil padrão (Novo, Em atendimento, Agendado, Compareceu, Venda fechada, Perdido) criado pela migração de clínica; `loss_reasons` configuráveis com lista inicial.
**Aceite:** etapas pertencem a um funil da mesma clínica; ordem consistente; todo funil tem exatamente uma etapa ganha e uma perdida; toda clínica nova recebe o funil padrão.
**Estado (10/11/2026):** concluída na branch `feat/crm-030-pipelines` (ADR-033). Desenho revisado pelo `architect`; as 6 decisões foram aprovadas pelo usuário, inclusive separar as escritas na CRM-036.
- **Banco:** migração de clínica V5 com `pipelines`, `pipeline_stages` e `loss_reasons`.
  - Um funil por clínica: o runtime só tem `SELECT`.
  - Exatamente uma etapa ganha e uma perdida, por índices únicos parciais; nunca arquivadas, com tipo imutável.
  - Posição 1..n só nas abertas ativas, única e `DEFERRABLE`, para a troca de posições caber num único `UPDATE`.
  - Chave estável (`NEW`, `IN_SERVICE`, `SCHEDULED`, `ATTENDED`, `WON`, `LOST`) nas etapas padrão; `NEW` nunca é arquivada.
  - Seed com ids aleatórios: funil padrão e 7 motivos (Preço, Sem resposta, Escolheu outra clínica, Sem horário disponível, Desistiu, Contato inválido, Outro). Só o seed fica sem autor, garantido por CHECK.
- **Módulo `pipeline`:** domínio que revalida as regras ao ler (funil fora delas → `CLINIC_DATA_INVALID_DATA`), entidades só de leitura e `PipelineRepository`.
- **Rotas** (todos os papéis, `LEAD_READ`): `GET /v1/clinic/pipeline` e `GET /v1/clinic/loss-reasons?includeArchived`.
- **Contrato:** `openapi.json` ganhou só as duas rotas.
- **Testes:** `./mvnw -B -ntp clean verify` → 240 unitários (2 ignorados no Windows) e 137 de integração, BUILD SUCCESS. Entre os novos:
  - `PipelineDomainTest` (4);
  - `PipelinePersistenceIT` (5):
    - funil padrão e motivos com nomes exatos;
    - ids diferentes por clínica e isolamento A/B, inclusive com acesso forjado;
    - 17 violações recusadas pelo banco como dono real da clínica;
    - troca de posições num único `UPDATE`;
    - inventário de privilégios do runtime (só `SELECT`);
    - funil inválido sai como `INVALID_DATA`.
  - `PipelineApiIT`: quatro papéis, sem clínica, participação revogada (403 e depois 409), seleção cruzada recusada, sem sessão.
  - `TenantMigrationRolloutIT`: agora remove e reaplica também a V5 e lê o funil depois do rollout.
- **Revisões:** `security` sem críticos, altos nem médios; `reviewer` sem críticos nem altos. Corrigidos: autoria dos motivos, testes como o dono real, inventário de privilégios, rollout lendo o funil, asserções exatas no HTTP e seleção cruzada.
- **Pendências:**
  - escritas da configuração na CRM-036;
  - a ordenação e a unicidade por `lower(name)` dependem da collation do banco da clínica, herdada do `template1` (en_US.utf8 na imagem atual; igual às tags);
  - nomes de etapa e motivo digitados pela clínica vão para o inventário da CRM-073.

### [ ] CRM-031 — Modelar leads, temperatura e eventos (P1)
**Depende de:** CRM-020, CRM-030.
**Desenho já decidido (ADR-033):** leads e eventos entram no módulo `pipeline`; o lead referencia a etapa pela chave composta `(pipeline_id, id)`; a etapa de entrada é a de chave `NEW`; o movimento trava a etapa com `FOR SHARE` e recusa etapa arquivada.
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

### [ ] CRM-036 — Configuração do funil e dos motivos de perda (P1)
**Depende de:** CRM-031, CRM-030.
**Atende:** CRM-01, CRM-06 (ADR-033).
**Entregas:**
- API com `COMMERCIAL_SETTINGS_EDIT` (Proprietário e Gestor): criar, renomear, reordenar, arquivar e desarquivar etapas abertas; renomear ganha, perdida e `NEW`; criar, renomear, arquivar e desarquivar motivos.
- Migração de clínica com grants por coluna ao runtime: `INSERT` em etapas e motivos; `UPDATE` só de nome, posição, autoria, arquivamento e versão; nunca de `id`, `pipeline_id`, `kind`, `system_key` nem `created_*`; sem `DELETE`.
- Tela de configuração (frontend).
**Aceite:**
- Reordenação trava o funil, compara a `version` (409 se mudou), recebe a lista completa das abertas e regrava 1..n num único `UPDATE`; duas reordenações concorrentes nunca deixam lacuna nem posição repetida.
- Arquivar etapa aberta renumera as demais na mesma transação e é recusado com lead aberto nela (`STAGE_HAS_OPEN_LEADS`), inclusive em corrida com o movimento de lead.
- Desarquivar coloca a etapa no fim das abertas.
- Pelo menos um motivo ativo sempre; limites de 12 etapas abertas e 50 motivos.
- Autores validados contra participação ativa; a CRM-036 sempre grava o autor.
- Matriz de permissões (Atendente e Somente leitura recebem 403) e A/B nas mutações.

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
- **Migrações:** ordem parar o provisionador antigo → `migrate` → provisionador novo → `tenant-migrate` → API nova, com tratamento do código de saída 1 (ADR-031, [provisionamento-clinicas.md](docs/provisionamento-clinicas.md)).
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

## Frontend em paralelo (Codex)

Executadas pelo Codex na pasta `crm/api-front`, conforme [docs/agents/PARALELO.md](docs/agents/PARALELO.md). Contrato: [docs/api/openapi.json](docs/api/openapi.json). Só tocam `frontend/`.

**Ajustadas em 07/11/2026** depois da CRM-018 (agências): FE-001, FE-002 e FE-004 ganharam itens, e a FE-005 é nova. **Em 09/11/2026**, depois da CRM-016 (segundo fator): FE-002 ajustada e FE-006 nova. Códigos de erro em [api-convencoes.md](docs/api-convencoes.md).

### [ ] FE-001 — Base do painel (P0)
**Depende de:** contrato atual (sessão, CSRF, erros).
**Entregas:**
- Cliente HTTP único:
  - chama `/api` na mesma origem e busca o CSRF em `GET /api/v1/auth/csrf`;
  - renova o CSRF depois de login e logout;
  - envia o cabeçalho indicado em toda mutação;
  - trata erros pelo `code`.
- Proteção das rotas do painel:
  - 401 leva ao login;
  - `SESSION_REVOKED` encerra a sessão local;
  - 403 mostra permissão negada;
  - `STATE_CONFLICT` (409) mostra que a operação conflita com o estado atual e sugere recarregar.
- Layout do painel (`/app`) responsivo, com área para a clínica atual.
- Componentes de estado: carregando, vazio, erro e sem permissão.
**Aceite:**
- Nenhum token em `localStorage` ou `sessionStorage`.
- Toda resposta de erro mostra a `message` do servidor ou um texto por `code`.
- `lint`, `typecheck` e `build` passam.

### [ ] FE-002 — Login, logout e seleção de clínica (P0)
**Depende de:** FE-001.
**Entregas:**
- Tela `/login`, com o login como formulário (`email`, `password`).
- Logout.
- Seleção da clínica a partir de `GET /api/v1/auth/clinics` e `PUT /api/v1/auth/clinic/{tenantId}`, com troca de clínica.
- Painel mostra a clínica e o papel atuais (`GET /api/v1/auth/clinic`).
- Destino depois do login, sempre consultando primeiro `GET /api/v1/auth/mfa` (o login continua respondendo 204):
  - `MFA_PENDING` vai para a verificação do segundo fator (FE-006);
  - `ENROLLMENT_REQUIRED` vai para o cadastro do segundo fator (FE-006);
  - só com `COMPLETE` seguem as regras abaixo;
  - quem tem clínicas segue para a seleção ou para o painel;
  - quem não tem clínica, mas administra agência (`GET /api/v1/agencies` não vazio), vai para `/agencia` (FE-005);
  - quem administra agência e também tem clínicas pode alternar entre as duas áreas;
  - quem não tem nenhum dos dois vê uma tela de "sem acesso" com o logout.
**Aceite:**
- `INVALID_CREDENTIALS` tem uma mensagem única, sem revelar se o e-mail existe.
- 429 respeita `Retry-After`.
- `CLINIC_NOT_SELECTED` leva à seleção.
- Acessível por teclado.

### [ ] FE-003 — Equipe e convites (P1)
**Depende de:** FE-002.
**Entregas:**
- Lista paginada da equipe com filtros `role` e `active`.
- Troca de papel e desativação.
- Convites pendentes: convidar, reenviar e revogar, mostrando `delivery` e vencidos.
- Ações exibidas pelas permissões de `GET /api/v1/auth/clinic`, só como dica; o servidor decide.
**Aceite:**
- Os códigos `MEMBER_CHANGE_FORBIDDEN`, `LAST_OWNER`, `INVITATION_FORBIDDEN`, `MEMBER_ALREADY_ACTIVE` e `INVITATION_SEND_LIMIT` têm mensagens claras.
- Estados vazio e de erro.
- Funciona no celular.

### [ ] FE-004 — Aceite de convite (P1)
**Depende de:** FE-001.
**Entregas:**
- Página `/convite`:
  - lê o token do fragmento (`#token=`) e o remove da barra com `history.replaceState`;
  - usa `Referrer-Policy: no-referrer`;
  - mostra a prévia (`POST /api/v1/invitations/preview`).
- A prévia diz o que o convite dá, pelo `kind`:
  - `CLINIC`: participar da clínica `targetName` com o papel `role`;
  - `AGENCY`: administrar a agência `targetName`.
- Aceite com nome e senha quando não há conta, ou pedido de login quando `requiresLogin`.
- Depois do aceite, encaminha ao login.
**Aceite:**
- O token nunca vai para a URL do servidor, para logs nem para storage.
- `INVITATION_INVALID`, `INVITATION_LOGIN_REQUIRED`, `INVITATION_ACCOUNT_MISMATCH` e `MEMBER_ALREADY_ACTIVE` têm mensagens próprias.
- A senha segue a regra do servidor (12 caracteres a 72 bytes), validada também no servidor.

### [ ] FE-005 — Carteira da agência (P1)
**Depende de:** FE-002.
**Entregas:**
- Área `/agencia`:
  - agências do usuário (`GET /api/v1/agencies`), com escolha quando houver mais de uma;
  - carteira paginada (`GET /api/v1/agencies/{agencyId}/clinics`) com a situação da clínica (`PROVISIONING`, `AWAITING_OWNER`, `ACTIVE`, `SUSPENDED`, `FAILED`) e o estado do convite do gestor (`PENDING`, `EXPIRED`, `ACCEPTED`, `NONE`), em texto e não só por cor.
- Formulário de nova clínica (`POST /api/v1/agencies/{agencyId}/clinics`):
  - campos: nome da clínica e e-mail do gestor;
  - o `requestId` é gerado no navegador (`crypto.randomUUID()`) ao abrir o formulário e reaproveitado em toda nova tentativa até dar certo, para que um duplo clique ou uma falha de rede não criem duas clínicas;
  - 201 mostra a clínica criada; 200 (`created: false`) avisa que ela já existia.
- Reemissão do convite do gestor (`POST /api/v1/agencies/{agencyId}/clinics/{tenantId}/owner-invitation`), só para clínicas `PROVISIONING` ou `AWAITING_OWNER` com convite diferente de `ACCEPTED`; pode trocar o e-mail.
- Mostrar `delivery: FAILED` com a opção de reenviar.
**Aceite:**
- Mensagens próprias para `AGENCY_NOT_FOUND`, `CLINIC_NOT_FOUND`, `IDEMPOTENCY_KEY_REUSED`, `AGENCY_PENDING_CLINIC_LIMIT`, `AGENCY_CLINIC_HAS_OWNER` e `INVITATION_SEND_LIMIT` (429, respeitando `Retry-After` quando houver).
- **Sem dados de pacientes:** a área da agência nunca mostra nem pede dados de pacientes, e não oferece abrir a clínica (a agência não tem participação; ADR-026).
- Estados de carregamento, vazio ("nenhuma clínica na carteira") e erro; funciona no celular e por teclado.
- `lint`, `typecheck` e `build` passam.

### [ ] FE-006 — Segundo fator: cadastro, verificação e gestão (P0)
**Depende de:** FE-001, FE-002 e o merge da CRM-016 (rotas `/api/v1/auth/mfa/**` no contrato).
**Contexto:** desde a CRM-016, dono de clínica e admin de agência só usam o sistema depois de cadastrar e verificar um app autenticador (TOTP). Sem estas telas, essas pessoas ficam paradas no primeiro login. Regras em [autenticacao.md](docs/autenticacao.md), seção "Segundo fator".
**Entregas:**
- **Estado:** `GET /api/v1/auth/mfa` (`state`, `enrolled`, `required`, `recoveryCodesRemaining`) decide a tela.
  - O cliente HTTP da FE-001 trata `MFA_REQUIRED` (401), levando à verificação, e `MFA_ENROLLMENT_REQUIRED` (403), levando ao cadastro, em qualquer rota.
  - Nenhum desses dois casos é tratado como sessão expirada.
- **Cadastro** (`ENROLLMENT_REQUIRED`, ou opcional pela área de segurança):
  - `POST /api/v1/auth/mfa/totp/setup` devolve `secret` e `otpauthUri` uma única vez;
  - mostrar a chave para digitar no app e, se a dependência de QR code for aprovada, o QR da `otpauthUri`;
  - campo para o código de 6 dígitos;
  - `POST /api/v1/auth/mfa/totp/confirm {code}`;
  - `MFA_SETUP_EXPIRED` (409) pede um setup novo.
- **Códigos de recuperação:**
  - a resposta do confirm traz os 10 códigos, mostrados uma única vez, com copiar e baixar como texto;
  - a pessoa confirma que guardou antes de seguir;
  - nunca vão para storage, URL nem log.
- **Verificação** (`MFA_PENDING`):
  - `POST /api/v1/auth/mfa/verify` com `{code}` ou, pela opção "usar código de recuperação", `{recoveryCode}`;
  - depois do 204, buscar o CSRF de novo, porque a sessão troca de id, e seguir as regras de destino da FE-002;
  - a sessão pendente vence em 5 minutos: um 401 `SESSION_REVOKED` leva de volta ao login com essa explicação.
- **Área "Segurança da conta"** (estado `COMPLETE`):
  - mostra se o fator está ativo e quantos códigos de recuperação restam;
  - gerar novos códigos (`POST /api/v1/auth/mfa/recovery-codes {code}`);
  - trocar o fator (setup e confirm com `{code}` atual);
  - desativar (`DELETE /api/v1/auth/mfa/totp {code}`) só para quem não é obrigado (`required: false`);
  - trocar ou desativar encerra todas as sessões, inclusive a atual: avisar antes e levar ao login depois.
**Aceite:**
- Mensagens próprias para:
  - `MFA_CODE_INVALID`;
  - `MFA_LOCKED` (429, mostrando a espera a partir de `Retry-After`, que pode chegar a 24 horas);
  - `MFA_SETUP_EXPIRED`;
  - `MFA_MANDATORY`;
  - `MFA_NOT_ENROLLED`;
  - `MFA_ALREADY_COMPLETE`.
- **Sigilo:** o segredo, a `otpauthUri` e os códigos de recuperação nunca vão para `localStorage`, `sessionStorage`, URL, título da página nem console. Ficam só na memória da tela e somem ao sair dela.
- **Acessibilidade:**
  - campo de código com `inputmode="numeric"`, `autocomplete="one-time-code"` e rótulo;
  - foco no campo ao abrir;
  - erros associados ao campo;
  - funciona por teclado e no celular.
- **Fluxo completo:** um dono de clínica novo consegue aceitar o convite, entrar, cadastrar o fator, guardar os códigos e chegar ao painel; no login seguinte, verifica com o app.
- **Checks:** `lint`, `typecheck` e `build` passam.
- **Dependência nova:** um gerador de QR code, por exemplo, exige aprovação do responsável. Sem ela, a tela funciona só com a chave em texto.

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
