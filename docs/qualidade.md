# Qualidade

Índice das verificações do Branly CRM. Convenções de backend: [padroes-backend.md](padroes-backend.md).

## Comandos e o que provam

| Escopo | Comando | Prova | Não prova |
| --- | --- | --- | --- |
| Backend | `.\mvnw.cmd -B -ntp clean verify` em `backend/` com JDK 21 e Docker | Formatação, compilação, unitários, ArchUnit e integração com PostgreSQL descartável (Testcontainers) | Comportamento no Compose, Nginx ou outro PostgreSQL |
| Backend | `.\mvnw.cmd spotless:apply` | Aplica a formatação exigida pelo `verify` | — |
| Frontend | `npm run lint`, `npx tsc --noEmit`, `npm run build` em `frontend/` | Lint, tipos e build de produção | Comportamento em execução |
| Ambiente | `docker compose build` e `docker compose up` na raiz | Imagens e ordem de inicialização | Isolamento entre clínicas |
| Papéis do banco | Procedimento em [credenciais-postgres.md](credenciais-postgres.md) | Privilégios dos papéis centrais | Credenciais por clínica |

Use `clean` depois de mover ou renomear pacotes: classes antigas em `target/` geram dois candidatos a classe principal.

## Cobertura atual dos testes de integração

- `BootstrapIT`: migrações V1–V4 em banco vazio, extensão vector; somente saúde e CSRF são públicos.
- `SessionAuthenticationIT`: cookie `BRANLY_SESSION` HttpOnly/SameSite, login sem CSRF (403), senha errada (401), normalização de e-mail, rotação do token CSRF, identidade sem credenciais, listagem só de clínicas ativas com participação ativa, seleção de clínica alheia/inexistente/suspensa/revogada com a mesma resposta 403, UUID inválido (400), revogação de participação, suspensão de clínica e desativação da conta valendo na requisição seguinte, conta não verificada igual a conta inexistente, logout e documentação OpenAPI com as rotas de filtro e o cookie de sessão.
- `TenantIsolationIT` (substitui `TenantConnectionIT`): provisionamento real sem superusuário, prova A/B, falha e retomada, concorrência e pools de conexão; ver [provisionamento-clinicas.md](provisionamento-clinicas.md).
- Antes, `TenantConnectionIT`: bancos de clínica reais criados pela migração `db/tenant-migration`, senha lida de arquivos publicados por `FileTenantSecretWriter`, conexão por TCP com o papel runtime da clínica, recusa quando `tenant_identity` diverge, quando o segredo falta ou não corresponde ao papel, e recusa antes de conectar sem participação.

- `TeamManagementIT`: papéis e gestão de equipe por HTTP:
  - clínica atual e permissões;
  - listagem por papel;
  - regras do MANAGER;
  - CSRF;
  - alvo de outra clínica com o mesmo 404;
  - triggers de último proprietário e de ativação;
  - dois proprietários se rebaixando ao mesmo tempo;
  - revogação na requisição seguinte.
- `TeamRuntimeGrantsIT`: as mesmas operações com o papel `crm_control_runtime` real, depois da redução de permissões da V6.
- `ClinicRoleTest` e `TeamPolicyTest`: matriz de [permissoes.md](permissoes.md) linha a linha e regras de gestão de equipe.

Testes de symlink e permissões POSIX de `FileTenantSecretStoreTest` são ignorados no Windows; rode a classe em Linux ao alterar o armazenamento de segredos (comando em [segredos-clinicas.md](segredos-clinicas.md)).

Ainda não cobertos: limitação de login em ambiente com várias réplicas e o caminho via Nginx com contexto `/api`.

## Revisão

Mudanças em autenticação, sessão, CSRF, tenant ou credenciais exigem revisão de segurança proporcional ao diff e testes de integração do fluxo alterado. Registrar comandos e resultados reais; não afirmar execução sem rodar.
