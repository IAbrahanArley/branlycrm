# Trabalho em paralelo: Claude Code no backend, Codex no frontend

Combinado em 07/11/2026. Complementa [ORCHESTRATION.md](ORCHESTRATION.md) e o §9 do [AGENTS.md](../../AGENTS.md): as regras de lá continuam valendo, este documento só divide o trabalho entre as duas ferramentas.

## Pastas e branches

Cada ferramenta trabalha numa pasta própria do mesmo repositório (git worktree), para nunca trocar a branch da outra:

| Ferramenta | Pasta | Branches |
|---|---|---|
| Claude Code (coordenador) | `crm/api` | `feat/…`, `fix/…`, `docs/…` de backend |
| Codex | `crm/api-front` | `feat/front-…`, `fix/front-…` |

Criar a pasta do Codex (uma vez, na raiz de `crm/api`):

```bash
git worktree add ../api-front -b feat/front-foundation main
```

Cada nova tarefa de frontend parte da `main` atualizada. Na pasta do Codex:

```bash
git switch main && git pull --ff-only && git switch -c feat/front-<tarefa>
```

## Quem escreve o quê

| Claude Code | Codex |
|---|---|
| `backend/`, `infra/`, `compose.yaml`, `.env.example` | `frontend/` inteiro, inclusive `package.json` e `package-lock.json` |
| `docs/adr/`, `TASKS.md`, `docs/api/openapi.json`, `AGENTS.md`, `docs/agents/` | Nada fora de `frontend/` |

- O Codex não edita `TASKS.md`. Ele descreve no PR o que fez, os checks e as limitações; o coordenador atualiza o backlog no merge.
- Dependência nova no frontend (por exemplo, um gerador de tipos ou uma biblioteca de formulários) exige aprovação do responsável, como qualquer outra.

## Contrato da API

- **Fonte:** [docs/api/openapi.json](../api/openapi.json), gerado a partir da API real.
- **Prefixo:** as rotas do contrato não incluem o contexto. O navegador chama `/api` + a rota, por exemplo `/api/v1/auth/me`.
- **Convenções:** erros, códigos e paginação em [api-convencoes.md](../api-convencoes.md); sessão, CSRF e convites em [autenticacao.md](../autenticacao.md); papéis e permissões em [permissoes.md](../permissoes.md).
- **Atualização:** o `OpenApiContractIT` falha quando a API muda e o arquivo não foi atualizado. Todo PR de backend que muda o contrato regenera o arquivo com `./mvnw verify -Dopenapi.write=true` e o inclui no mesmo PR.
- **Faltou algo na API?** O Codex não inventa rota nem campo e não contorna o servidor. Ele descreve a necessidade no PR (rota, campos, códigos de erro), e o coordenador faz no backend.

## Ambiente

- **Backend:** um só Compose, rodando a partir de `crm/api` (`docker compose up -d --build --wait`). Expõe `http://localhost:8088`, com a API em `/api`.
- **Frontend:** o Codex roda `npm run dev` em `crm/api-front/frontend`, na porta 3000. O `next.config.ts` repassa `/api` para `localhost:8088`, a mesma regra que a Vercel usa em produção (`API_PROXY_TARGET`). Cookie e CSRF funcionam como em produção.
- **Dados:** contas e clínicas de teste só sintéticas (`@example.test`). E-mails locais viram arquivos dentro do container da API (`/tmp/branly-mail`); para ver um link de convite: `docker compose exec backend sh -c 'cat /tmp/branly-mail/*'`.

## Ordem de merge e checks

1. **Backend primeiro.** PRs de backend entram antes dos PRs de frontend que dependem deles.
2. **Frontend sobre a `main`.** Depois de cada merge de backend, a branch de frontend em andamento é atualizada com a `main`.
3. **Checks antes do PR, enquanto a CI do GitHub estiver parada:**
   - **backend:** `./mvnw -B -ntp clean verify`;
   - **frontend:** `npm ci`, `npm run lint`, `npm run typecheck` e `npm run build`. Quando a tela usa a API, também conferir no navegador com o Compose rodando.
4. **Merge:** só o responsável faz.

## Primeira mensagem para o Codex

> Leia `AGENTS.md`, `docs/agents/FRONTEND.md`, `docs/agents/PARALELO.md`, `docs/api-convencoes.md`, `docs/autenticacao.md` e `docs/permissoes.md`. Você é o papel **frontend** deste repositório e só escreve em `frontend/`. O contrato da API é `docs/api/openapi.json`; o navegador chama `/api` + a rota. Não edite `TASKS.md` nem nada fora de `frontend/`.
>
> Faça a tarefa **FE-001** do `TASKS.md` (base do painel). Antes de codar, leia em `frontend/node_modules/next/dist/docs/` o que for usar desta versão do Next, proponha o plano (arquivos, componentes, estados, checks) e aguarde minha aprovação. Ao terminar, rode `npm run lint`, `npm run typecheck` e `npm run build` e entregue no formato de handoff do `AGENTS.md`. Se faltar algo na API, descreva a necessidade em vez de contornar.
