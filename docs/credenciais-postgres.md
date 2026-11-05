# Credenciais do PostgreSQL

Implementado em 05/11/2026. ADR-016. Esta é a primeira parte da etapa 1 do onboarding; os bancos das clínicas ainda não são provisionados automaticamente.

## Separação

| Papel | Uso | Privilégios |
|---|---|---|
| POSTGRES_USER | Bootstrap administrativo local | Somente contêiner PostgreSQL e db-bootstrap; nunca API |
| crm_control_runtime | Requisições da API | CONNECT, USAGE e SELECT/INSERT/UPDATE/DELETE nas tabelas centrais autorizadas; somente leitura do histórico Flyway |
| crm_control_migrator | Migrações centrais | Proprietário das tabelas e funções da aplicação, CREATE no schema; sem SUPERUSER/CREATEDB/CREATEROLE |
| crm_tenant_provisioner | Provisionador (ADR-018) | CREATEDB/CREATEROLE sem superusuário; CONNECT só em `postgres` e nos bancos de clínica que cria, nunca no controle; credencial não entregue à API |
| crm_provisioning_worker | Fila do provisionador no controle | Tarefas, status de tenants, inclusão do ADMIN e leitura do estado das contas, sem `password_hash`; credencial não entregue à API |

API não recebe senhas de administrador, migrador ou provisionador. O provisionador ainda não possui worker nem fluxo de criação de clínica. Antes de conectá-lo, revisar concessões e validar credenciais cruzadas entre bancos de clínicas.

## Inicialização

postgres saudável → db-bootstrap concluído → migrate concluído → backend saudável → frontend → Nginx.

db-bootstrap executa infra/postgres/bootstrap-roles.sh tanto em volume novo quanto existente. Cria/atualiza os três papéis fixos, prepara a extensão vector para a migração histórica V1, restringe PUBLIC e transfere somente tabelas/funções centrais conhecidas ao migrador. Não usa REASSIGN OWNED e não remove bancos ou dados. É uma operação administrativa local e deve ser executada com a aplicação em manutenção ao aplicar mudanças de grants em produção.

migrate usa a mesma imagem Java, com APP_MODE=migrate; executa Flyway sem iniciar servidor web. Recebe apenas credencial de migração. API usa SPRING_FLYWAY_ENABLED=false e só recebe a credencial runtime. A migração V4 concede os privilégios de dados necessários ao runtime. Migrações futuras devem conceder explicitamente acesso às novas tabelas; não há grants automáticos indiscriminados.

## Segredos

.env ignorado pelo Git: CONTROL_RUNTIME_PASSWORD, CONTROL_MIGRATION_PASSWORD e TENANT_PROVISIONER_PASSWORD, além da senha administrativa já existente. Foram geradas senhas locais distintas sem alterar a senha administrativa. .env.example contém apenas exemplos.

O bootstrap reaplica senhas dos três papéis a partir do .env. Para rotacionar, atualizar o segredo e recriar bootstrap/serviços dependentes; reiniciar pools e verificar operações. Não presumir que alterar POSTGRES_PASSWORD modifica automaticamente a senha administrativa de um volume existente. Para hospedagem, substituir armazenamento local por segredos gerenciados e definir procedimento de rotação auditado.

## Verificação reproduzível

PowerShell, na raiz:

```powershell
$verifyPath = Join-Path (Get-Location) 'infra/postgres/verify-roles.sh'
docker compose run --rm --no-deps --volume "${verifyPath}:/verify.sh:ro" --entrypoint sh db-bootstrap /verify.sh
```

A verificação confirma runtime sem privilégios elevados; recusa CREATE TABLE/CREATE ROLE; histórico Flyway sem UPDATE; provisionador sem CONNECT ao controle; leitura de tabela de aplicação permitida. Operações de teste são revertidas.

Também foi validada instalação completa em banco vazio temporário (V1–V4), removido ao final, e repetido o teste HTTP de login/CSRF/seleção/revogação/logout com limpeza dos dados temporários.

## Pendências

- Papéis por clínica e rejeição A → B implementados e testados ([provisionamento-clinicas.md](provisionamento-clinicas.md)). O bootstrap também revoga CONNECT de PUBLIC em `postgres`/`template1` e prepara vector em `template1`.
- Armazenamento local e rotação versionada de segredos de clínicas implementados ([segredos-clinicas.md](segredos-clinicas.md)); faltam armazenamento gerenciado da hospedagem e pools.
- Separar JPA central e tenant antes dos dados de domínio.
- Automatizar cadastro, e-mail e provisionamento.
- Privilégios atuais são para o ambiente local; CREATEROLE/CREATEDB precisam permanecer restritos ao processo provisionador.
