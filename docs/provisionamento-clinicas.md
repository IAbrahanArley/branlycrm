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

- **Pela agência:** `POST /v1/agencies/{agencyId}/clinics` cria, numa transação, a clínica (`PROVISIONING`, com `agency_id`), a tarefa sem administrador e o convite do gestor. O provisionador deixa a clínica `AWAITING_OWNER`, e o aceite a ativa (ADR-028).
- **Pela plataforma:** agências e admins de agência são criados pelo comando de plataforma (seção abaixo).
- **Cadastro público** (clínica direta): continua previsto na CRM-014.

## Rollout de migrações de clínica

Uma clínica criada antes de uma migração nova de `db/tenant-migration` só a recebe por este comando (ADR-031). Sem ele, a tabela ausente falha fechada com `CLINIC_DATA_SCHEMA_OUTDATED`.

```bash
docker compose --profile tools run --rm tenant-migrate
docker compose --profile tools run --rm tenant-migrate --tenant-id <uuid>
```

- **Processo:** `APP_MODE=tenant-migrate`, sem HTTP, executado uma vez. Recebe as credenciais do provisionador e o volume de segredos só para leitura. Não recebe o gravador de segredos, a fila nem o scheduler.
- **Alcance:** clínicas `ACTIVE`, `AWAITING_OWNER` e `SUSPENDED`, uma por vez, em ordem de UUID. `PROVISIONING` e `FAILED` seguem pela fila.
- **Por clínica:** pré-checagem só de leitura (banco e dono, papéis, `tenant_identity`), migrações como `owner_<id>` com a checagem de ownership, e a mesma verificação de login do runtime da etapa VERIFICATION.
- **Saída:** uma linha por clínica e um resumo, por exemplo `tenantId=<uuid> result=MIGRATED stage=VERIFICATION applied=1` ou `result=FAILED stage=PRECHECK applied=0 code=DATABASE_MISSING`. Só UUID e códigos estáveis; nenhum SQL, segredo ou dado pessoal.
- **Código de saída:** 0 se todas passaram; 1 se alguma falhou ou o banco central ficou indisponível; 2 em uso inválido.
- **Falha e retomada:** a falha de uma clínica não para as outras. Basta rodar de novo: o Flyway pula o aplicado e trava cada banco, então duas execuções simultâneas, ou o rollout junto com o provisionador, aplicam cada migração uma vez. `VERIFICATION_FAILED` com `applied` maior que zero significa schema já atualizado e verificação a repetir, por exemplo com o runtime no limite de conexões.
- **Códigos:** `TENANT_NOT_ELIGIBLE`, `DATABASE_MISSING`, `DATABASE_CONFLICT`, `ROLE_CONFLICT`, `IDENTITY_MISMATCH`, `PRECHECK_FAILED`, `MIGRATION_FAILED`, `OWNERSHIP_CONFLICT`, `SECRET_MISSING`, `SECRET_<motivo>` e `VERIFICATION_FAILED`. `DATABASE_CONFLICT`, `ROLE_CONFLICT` e `IDENTITY_MISMATCH` numa clínica ativa são incidentes: investigar antes de reexecutar.
- **Lock:** a sessão de migração usa `lock_timeout` de 10 s, também no provisionamento; um DDL bloqueado por transações da API falha e é reexecutado depois.
- **Provisionamento:** a etapa VERIFICATION migra de novo antes de verificar. Uma tarefa que passou da MIGRATION com uma imagem antiga não ativa a clínica com schema antigo.
- **Privilégios:** o processo usa as credenciais completas do worker e do provisionador. O ArchUnit garante que só o worker grava segredos, mexe na fila e no registro e chama `ensureRoles`/`ensureDatabase`; o banco ainda não restringe isso (risco aceito na ADR-031).

### Ordem no deploy

1. Parar o provisionador antigo. Rodando, ele ativaria clínicas com o schema antigo depois de o rollout listar as clínicas.
2. `migrate` (banco central).
3. Provisionador com a imagem nova: a etapa VERIFICATION já aplica as migrações novas.
4. `tenant-migrate`; com código 1, tratar as clínicas falhas e reexecutar.
5. API nova.

As migrações de clínica são aditivas: a API antiga funciona sobre o schema novo, e remover coluna ou tabela exige um deploy posterior, quando nenhuma imagem em uso depender dela. Em produção, cada execução exige aprovação humana (AGENTS.md §10). O runbook completo entra na CRM-072.

## Comando de plataforma

Processo sem HTTP (`APP_MODE=platform`) com a credencial `crm_platform_operator`, que só cria agências e convites de admin de agência:

```bash
docker compose --profile tools run --rm platform create-agency --request-id <uuid> --name "<nome>" --admin-email <e-mail>
docker compose --profile tools run --rm platform invite-agency-admin --agency-id <uuid> --email <e-mail>
```

- **Saída:** o link do convite (validade de 72 horas) sai uma única vez na saída padrão, sem o e-mail, nunca no logger nem por e-mail. O serviço não guarda log no Docker (`logging: none`) e deve rodar sempre com `--rm`.
- **Permissões:** o operador só cria agências e chama `platform_issue_agency_admin_invitation` (V12); não lê nem altera convites, contas, clínicas ou participações.
- **Validação:** flags desconhecidas ou repetidas são recusadas, e um `--request-id` já usado com outro nome também.
- **Repetição:** repetir o comando reaproveita a agência e gira o convite pendente, e o link anterior para de funcionar.
- **Auditoria:** ainda não há auditoria central dessas operações (CRM-070).

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

Criada na CRM-015 (ADR-030), com `clinic_settings` como primeira entidade. Clínicas anteriores à V3 recebem a tabela pelo rollout (seção acima).
- `ClinicPersistence` guarda um `SessionFactory` privado; as conexões vêm de `TenantConnectionRepository.open`, sobre `TenantConnectionPools`, com o tenant passado explicitamente, sem ThreadLocal.
- A JPA central declara `PersistenceManagedTypes` vazio, então nenhuma entidade cai no EntityManager central.
- O ArchUnit exige `@Entity` só em pacotes `persistence`, JPA e Hibernate só em `persistence` e `repositories`, e os pacotes `persistence` usados só por repositórios.
- `ClinicPersistenceIT` prova: A/B de leitura e escrita; recusa antes de abrir pool; participação desativada; rollback; grants do runtime; tabela ausente com código estável; sessão sem tenant recusada; metamodel central vazio; mapeamento validado contra a clínica provisionada.

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

`TenantMigrationRolloutIT` provisiona clínicas reais e as deixa sem a V3 e a V4 (objetos e linhas do histórico removidos como dono). Cobre:
- a clínica volta a funcionar por `ClinicSettingsRepository` e pelas chamadas das rotas de contato (`ContactService`), e a reexecução sai `UP_TO_DATE`;
- falha isolada entre duas clínicas, sem texto SQL na saída, e retomada;
- `SUSPENDED` migrada, `PROVISIONING` e `FAILED` ignoradas;
- clínica sem banco recusada sem criar banco;
- `--tenant-id` restrito à clínica pedida;
- duas execuções simultâneas aplicam a migração uma vez.

`TenantMigrationRolloutTest` cobre uso inválido, ordem das etapas, códigos e a separação de beans entre os modos.

Validado também no Compose: o bootstrap, a V5, o script de papéis e um provisionamento completo de clínica sintética chegaram a `ACTIVE`, com todos os objetos removidos depois.
