# Provisionamento de clínicas

Implementado em 05/11/2026. Itens 2 a 5 de [plano-isolamento-clinicas.md](plano-isolamento-clinicas.md); decisões na ADR-018.

## Processo e credenciais

O provisionador é a mesma imagem do backend executada com `APP_MODE=provision`, sem servidor HTTP, no serviço `provisioner` do Compose. Só esse processo recebe:

| Credencial | Uso |
|---|---|
| `crm_provisioning_worker` | Banco central. Fila de tarefas; status da clínica; inclusão do ADMIN; leitura do estado da conta (`subject_id`, `active`, `email_verified`), nunca de `password_hash`. |
| `crm_tenant_provisioner` | CREATEDB/CREATEROLE sem superusuário. Conecta apenas ao banco de manutenção `postgres` e aos bancos de clínica que criou; não tem CONNECT no banco central. |
| Volume `tenant_secrets` com escrita | Publicação dos segredos com `FileTenantSecretWriter`. |

A API não recebe nenhuma delas: `ProvisioningConfiguration` só existe com `app.provisioning.enabled=true`, e o ArchUnit impede que os módulos `identity` e `tenant` dependam de `provisioning`.

## Objetos por clínica

| Objeto | Detalhe |
|---|---|
| `owner_<id>` | NOLOGIN, NOINHERIT. Dono do banco e de todos os objetos. |
| `tenant_<id>` | LOGIN, NOINHERIT, sem atributos elevados nem participação em outros papéis, CONNECTION LIMIT 20. Papel runtime usado pela API. |
| `clinic_<id>` | Banco criado a partir de `template1`, dono `owner_<id>`, sem privilégios para PUBLIC; CONNECT apenas para o runtime e o provisionador. |

O provisionador recebe `owner_<id>` com SET e sem INHERIT. Assim, ele só age como dono quando executa DDL e migrações explicitamente, e não herda acesso aos dados. No PostgreSQL 16+, quem cria um papel recebe ADMIN sobre ele. O provisionador usa isso como prova de que um papel existente é dele antes de reutilizá-lo.

Diferença em relação ao plano: não há papel `migrator_<id>` com login. As migrações de clínica são aplicadas pelo provisionador com `SET ROLE owner_<id>`, o que evita uma segunda senha por clínica. O histórico do Flyway registra `owner_<id>` como `installed_by`.

## Etapas

`SECRET → ROLES → DATABASE → MIGRATION → VERIFICATION → ACTIVATION`, registradas em `tenant_provisioning_tasks` (V5).

1. **SECRET:** cria o segredo, ou reutiliza o existente, para que a senha do papel nunca diverja do arquivo.
2. **ROLES:** cria ou reconcilia os papéis. Recusa um papel existente que tenha login, atributos ou participações inesperados (`ROLE_CONFLICT`).
3. **DATABASE:** cria o banco fora de transação. Recusa um banco existente com outro dono (`DATABASE_CONFLICT`). Remove os privilégios de PUBLIC e concede apenas CONNECT.
4. **MIGRATION:** aplica `db/tenant-migration` como dono, com os placeholders `tenantId` e `runtimeRole`. A V2 grava `tenant_identity` e concede ao runtime somente `USAGE` no schema e `SELECT` na identidade. Depois, confere que todo objeto do schema pertence ao dono.
5. **VERIFICATION:** faz login como runtime por TCP. Confere o usuário, o banco e a identidade, e que o runtime não tem escrita na identidade, acesso ao histórico do Flyway, CREATE, TEMP, atributos elevados nem participações. Confere também que o CONNECT é negado no banco central e em `postgres`.
6. **ACTIVATION:** numa única transação do banco central.
   - **Com administrador** (caminho do cadastro público, CRM-014): exige um administrador ativo e verificado, cria a participação `OWNER`, passa a clínica de `PROVISIONING` para `ACTIVE` e conclui a tarefa.
   - **Sem administrador** (clínica criada por agência, ADR-028): trava a linha da clínica e não inventa dono. Se o gestor convidado já aceitou durante o provisionamento, a clínica vai para `ACTIVE`; senão, para `AWAITING_OWNER`, e o aceite do convite a ativa por `activate_awaiting_clinic`. O aceite trava a mesma linha antes de criar o `OWNER`, então nenhum dos dois lados deixa a clínica parada com dono.

A tarefa é assumida com `FOR UPDATE SKIP LOCKED` e um prazo de posse (`lease`, 5 min). O número da tentativa funciona como token: um processo que perdeu a posse não grava progresso. Em caso de falha:
- grava só um código estável em `last_error`; nenhum SQL, segredo ou stack trace vai para o banco ou para o log;
- tenta de novo depois de `backoff × tentativa`, retomando da etapa registrada;
- esgotadas as tentativas, marca a tarefa e a clínica como `FAILED`. `TenantProvisioningService.retryFailed` retoma com o mesmo UUID.

Nenhum banco é apagado automaticamente.

## Disparo

Ainda não há cadastro público: a criação da tarefa (clínica `PROVISIONING` mais a linha em `tenant_provisioning_tasks`) virá na etapa 2 do [plano de onboarding](plano-onboarding.md), na mesma transação do pedido de cadastro. Até lá, as tarefas são criadas apenas por testes, ou manualmente em ambiente local com dados sintéticos.

## Pools de conexão

`TenantConnectionPools` cria pools Hikari sob demanda, um por clínica e versão de segredo:

| Parâmetro | Padrão | Uso |
|---|---|---|
| `app.tenancy.pool.max-pools` | 20 | Clínicas com pool aberto ao mesmo tempo |
| `app.tenancy.pool.max-per-tenant` | 3 | Conexões por clínica (teto global = 60) |
| `app.tenancy.pool.idle-expiry` | PT10M | Fecha o pool de uma clínica ociosa |

- **Teto atingido:** o pool ocioso usado há mais tempo é fechado. Se todos estiverem ocupados, a operação falha imediatamente (`Tenant connection capacity exhausted`).
- **Rotação de segredo:** a nova versão passa a valer na operação seguinte. O pool antigo só é fechado quando suas conexões emprestadas voltam, sem abortar operações em andamento.
- **Identidade:** é conferida a cada empréstimo.

## Persistência JPA de clínica

Ainda não foi criada, por decisão registrada na ADR-018: não há entidade de clínica para validar. Ela virá com a primeira entidade de negócio, sobre `TenantConnectionPools` e com o tenant passado explicitamente, sem ThreadLocal. Até lá, o ArchUnit proíbe `@Entity`, para que nenhuma entidade caia no EntityManager central.

## Verificação

`ControlRuntimeGrantsIT` roda como `crm_control_runtime` e prova as permissões da V8: a API não cria clínicas, não muda status nem agência, não grava contas nem participações, e só ativa clínica `AWAITING_OWNER` com dono. Também cobre as transições recusadas e a chave de idempotência por agência.

`TenantIsolationIT` cria clínicas pelo provisionador real, com papéis sem superusuário, usando o worker como datasource da aplicação. Cobre:
- **A/B:** runtime A recebe CONNECT negado (42501) em B, no banco central, em `postgres` e em `template1`, e vice-versa. Recusa CREATE TABLE, TEMP, ROLE e DATABASE, escrita em `tenant_identity`, leitura do histórico, `SET ROLE` e `GRANT` do dono.
- **Privilégios:** migrações como dono; runtime sem participações; provisionador com SET sem INHERIT; provisionador sem acesso ao banco central; worker sem acesso a `password_hash`.
- **Sem administrador:** a clínica termina em `AWAITING_OWNER` sem nenhuma participação, e só fica acessível depois do dono e da ativação; com o dono aceito durante o provisionamento, termina `ACTIVE`.
- **Falha e retomada:** falha na ativação até `FAILED`, retomada administrativa e repetição completa de todas as etapas sem duplicar papéis, banco, participação, segredo, histórico ou identidade.
- **Concorrência:** 4 workers simultâneos provisionam a clínica uma única vez.
- **Falha fechada:** participação ausente, clínica suspensa, identidade divergente, senha divergente e segredo ausente.
- **Pools:** rotação sem abortar conexões emprestadas, teto global com despejo do pool ocioso mais antigo, limite por clínica e expiração por ociosidade.

Validado também no Compose: o bootstrap, a V5, o script de papéis e um provisionamento completo de clínica sintética chegaram a `ACTIVE`, com todos os objetos removidos depois.
