# Multitenancy — banco por clínica

Data: 05/11/2026. Decisão vigente: ADR-014, substituindo banco compartilhado da ADR-002.

## Separação

Banco central: tenants e tenant_memberships. Cada clínica: banco clinic_<uuid sem hífens>, papel tenant_<uuid sem hífens> e referência de segredo TENANT_SECRET_<UUID sem hífens em maiúsculas>. O segredo fica no servidor/gerenciador de segredos; apenas sua referência vai ao registro. A API só lê, por TenantSecretReader; ver [segredos-clinicas.md](segredos-clinicas.md).

O tenant_id é permanente. No cadastro futuro, o servidor gera UUID e cria registro PROVISIONING; o provisionador cria banco/papel, aplica migrações, insere tenant_identity e verifica isolamento antes de ativar. Falhas deixam registro FAILED e permitem retomada controlada sem gerar outro UUID para a mesma tentativa de cadastro.

## Acesso

TenantAccessService verifica clínica ACTIVE e participação ativa do sujeito autenticado. TenantConnectionRepository (ADR-017) repete essa verificação no registro, busca segredo e confere tenant_identity antes de devolver a conexão. O chamador fecha a conexão com try-with-resources. Não existe fallback. Não usar ID de usuário fornecido pelo cliente como authenticatedSubject.

Esta etapa não adiciona endpoints públicos, seleção por header ou login. A integração dos serviços com o sujeito autenticado será feita no módulo de identidade. Conexão por operação é provisória; pools com limite global, expiração e fechamento precisam ser implementados antes de escalar.

A JPA central não gerencia nenhuma entidade (`ControlJpaConfiguration`). Entidades de clínica usam a unidade própria `ClinicPersistence` (ADR-030): sessão aberta com tenant explícito, conexões só por `TenantConnectionRepository.open` e uma transação por chamada, dentro do repositório. Cada módulo registra as próprias entidades com um bean `ClinicEntityRegistration`.

## Migrações e integridade

- db/migration: migrações do banco central, executadas pelo Flyway da aplicação.
- db/tenant-migration: migrações de clínicas, aplicadas pelo provisionador como dono; NÃO são executadas no banco central. Ainda não há comando que aplique migrações novas a clínicas já existentes: até ele existir, tabela ausente falha fechada com `CLINIC_DATA_SCHEMA_OUTDATED`.
- V2 central garante UUID, nomes vinculados, unicidade, campos obrigatórios, estados válidos, participação sem duplicação e imutabilidade do vínculo banco/tenant.
- V1 tenant cria vector e tenant_identity com no máximo uma linha. Provisionamento insere a identidade antes de ativar e concede somente SELECT dessa tabela ao papel runtime.
- V3 tenant cria `clinic_settings` (fuso, linha única), com `SELECT` e `UPDATE (time_zone)` para o runtime.
- V4 tenant cria `contacts` (telefone E.164 único, inclusive arquivados), `tags`, `contact_tags` e `contact_consents` (só inserção), com a extensão `pg_trgm` e grants por coluna (ADR-032).
- As demais tabelas de negócio (funil, leads, vendas, conversas) definem as próprias constraints nas migrações correspondentes.

## Requisitos do provisionador

Implementados em 05/11/2026; ver [provisionamento-clinicas.md](provisionamento-clinicas.md). Lista original:

1. Validar identidade, idempotência e dados da clínica.
2. Reservar UUID e nomes imutáveis em PROVISIONING.
3. Criar papel proprietário/migração separado do papel runtime; runtime sem SUPERUSER/CREATEDB/CREATEROLE.
4. Criar banco fora da transação central e revogar CONNECT de PUBLIC; permitir somente papéis autorizados.
5. Aplicar migrações, gravar identidade, conceder apenas permissões necessárias e armazenar segredo.
6. Testar que credencial A não conecta ao banco B, nem ao controle, e que não altera identidade/esquema.
7. Ativar e criar participação do administrador após sucesso; tratar falhas parciais sem apagar bancos com dados.

A senha de cada clínica não está provisionada pelo Compose. O perfil local ainda usa o usuário administrativo do PostgreSQL no controle; separar credenciais é condição de entrada para onboarding real. Backup por clínica, retenção, recuperação e versão de schema devem integrar o provisionamento.

## Cenários adicionais

- SC-017: provisionamento. AC-01 UUID é gerado no servidor; AC-02 clínica não pode operar enquanto PROVISIONING/FAILED; AC-03 só ativar após migração e isolamento; AC-04 repetição reutiliza identidade da tentativa; AC-05 falha não produz acesso parcial.
- SC-018: acesso ao banco. AC-01 sujeito sem participação é recusado; AC-02 suspensão e revogação bloqueiam novas conexões; AC-03 referência de outro banco é rejeitada; AC-04 segredo ausente não provoca fallback; AC-05 identidade incompatível fecha conexão; AC-06 credencial A não conecta ao banco B.

SC-017 e SC-018 são verificados por TenantIsolationIT desde 05/11/2026. O disparo de SC-017 pelo cadastro público ainda depende do onboarding.

## Verificações executadas

Backend compilado no Docker; migrações V1/V2 aplicadas; três testes Java passaram (identidade ausente, tenant desconhecido, vínculo de outro banco). No PostgreSQL foram verificadas rejeições de participação sem tenant, alteração de vínculo, nome vazio e estado inválido, em transação revertida. Nenhum banco real de clínica foi criado nesta etapa e isolamento cruzado entre credenciais ainda não foi testado.
