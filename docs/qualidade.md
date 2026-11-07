# Qualidade

Índice das verificações do Branly CRM. Convenções de backend: [padroes-backend.md](padroes-backend.md).

## Comandos e o que provam

| Escopo | Comando | Prova | Não prova |
| --- | --- | --- | --- |
| Backend | `.\mvnw.cmd -B -ntp clean verify` em `backend/` com JDK 21 e Docker | Formatação, compilação, unitários, ArchUnit e integração com PostgreSQL descartável (Testcontainers) | Comportamento no Compose, Nginx ou outro PostgreSQL |
| Backend | `.\mvnw.cmd spotless:apply` | Aplica a formatação exigida pelo `verify` | — |
| Frontend | `npm run lint`, `npm run typecheck`, `npm run build` em `frontend/` | Lint sem avisos, tipos de rota e TypeScript, build de produção | Comportamento em execução |
| Ambiente | `docker compose build` e `docker compose up` na raiz | Imagens e ordem de inicialização | Isolamento entre clínicas |
| Papéis do banco | Procedimento em [credenciais-postgres.md](credenciais-postgres.md) | Privilégios dos papéis centrais | Credenciais por clínica |

**CI:** `.github/workflows/ci.yml` roda os mesmos checks em todo PR e em push na `main`, sem deploy nem segredos. O job do backend executa `./mvnw -B -ntp verify` (Testcontainers usa o Docker do runner). O job do frontend executa `npm ci`, `npm run lint` (zero avisos), `npm run typecheck` (`next typegen` + `tsc --noEmit`) e `npm run build`. As actions são fixadas por SHA, e o Maven baixado pelo wrapper é conferido por `distributionSha256Sum`.

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
- `ApiConventionsIT`: corpo de erro padrão e códigos (401, credenciais inválidas com corpo único, CSRF, clínica, entrada inválida com `fields`, rota inexistente, método não permitido), ausência de SQL e de dados pessoais nos erros, paginação estável com total e filtros, limite de OFFSET e 429 do login.
- `TeamInvitationIT` (como `crm_control_runtime`, HTTP com cookies e CSRF):
  - convite e aceite sem conta, com login depois; só o hash do token no banco;
  - tokens desconhecidos, vencidos, revogados e girados com a mesma resposta;
  - conta verificada exige login da própria conta, sessão de outra conta é recusada e a senha não muda;
  - conta inativa recusada; conta não verificada tomada pelo token com sessões antigas revogadas;
  - alcance do `MANAGER`, membro ativo, ex-membro reativado, emissor rebaixado e convite de outra clínica com 404.
- `InvitationAcceptanceIT`: quatro aceites simultâneos do mesmo token criam uma conta e uma participação; convite de gestor da agência ativa clínica `AWAITING_OWNER` ou espera o provisionamento; morre com clínica que já tem dono, de outra agência, emissor que saiu ou agência suspensa; 429 nas rotas anônimas.
- `InvitationAcceptanceIT` também cobre:
  - convite de agência recusado em clínica que já teve dono ou em `FAILED`, e quando o próprio admin da agência tenta aceitar;
  - dois convites para o mesmo e-mail novo ao mesmo tempo: uma conta, o outro recebe `INVITATION_LOGIN_REQUIRED` e continua pendente;
  - preview e aceite sem CSRF recebem 403.
- `InvitationDomainTest`: token, e-mail, senha, origem pública e alcance de convite.
- `OpenApiContractIT`: o arquivo `docs/api/openapi.json`, que o frontend usa, é igual ao que a API documenta. Ao mudar o contrato, regenerar com `./mvnw verify -Dopenapi.write=true`.
- `ControlRuntimeGrantsIT`: banco central como `crm_control_runtime` depois da V8. A API não cria clínicas, não muda status nem agência e não grava contas nem participações. `updated_at` só serve para travar. `activate_awaiting_clinic` só ativa `AWAITING_OWNER` com dono. Cobre também as transições recusadas e a chave de idempotência por agência.
- `ClientRolesMigrationIT`: V7 aplicada sobre dados da V6: conversão dos papéis, `active` preservado, nomes antigos recusados pela constraint e trigger de último proprietário ainda ativo.
- `PageQueryTest` e `TeamQueryTest`: limites e validação de paginação e filtros.
- `TeamRuntimeGrantsIT`: as mesmas operações com o papel `crm_control_runtime` real, depois da redução de permissões da V6.
- `ClinicRoleTest` e `TeamPolicyTest`: matriz de [permissoes.md](permissoes.md) linha a linha e regras de gestão de equipe.

Testes de symlink e permissões POSIX de `FileTenantSecretStoreTest` são ignorados no Windows; rode a classe em Linux ao alterar o armazenamento de segredos (comando em [segredos-clinicas.md](segredos-clinicas.md)).

Ainda não cobertos: limitação de login em ambiente com várias réplicas e o caminho via Nginx com contexto `/api`.

## Revisão

Mudanças em autenticação, sessão, CSRF, tenant ou credenciais exigem revisão de segurança proporcional ao diff e testes de integração do fluxo alterado. Registrar comandos e resultados reais; não afirmar execução sem rodar.
