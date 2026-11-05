# AGENTS.md — Instruções para agentes de desenvolvimento

> Projeto: **Branly CRM**, SaaS de CRM comercial e relacionamento para clínicas de estética.
> Produto: [`docs/planejamento-crm-clinicas-estetica.md`](docs/planejamento-crm-clinicas-estetica.md). Backlog e estado: [`TASKS.md`](TASKS.md). Decisões: [`docs/adr/decisoes.md`](docs/adr/decisoes.md).
> Codex lê este arquivo diretamente; Claude Code o importa por `CLAUDE.md`. Ele define como trabalhar; o estado real está no código e em `TASKS.md`.

## 1. Missão e limites

Construir um MVP B2B multi-tenant para clínicas captarem e organizarem contatos, gerenciarem oportunidades, tarefas, avaliações comerciais e relatórios. **Não** implementar prontuário, diagnóstico, prescrição, financeiro clínico, WhatsApp integrado, campanhas automatizadas ou IA sem autorização explícita (ADR-019). Evitar guardar dados de saúde não necessários ao CRM.

## 2. Ordem de leitura obrigatória

1. Este `AGENTS.md`.
2. `docs/planejamento-crm-clinicas-estetica.md` (visão e requisitos).
3. `TASKS.md` (ordem, dependências, aceite e estado verificado).
4. As ADRs relevantes, [`docs/padroes-backend.md`](docs/padroes-backend.md) e [`docs/qualidade.md`](docs/qualidade.md). Para clínicas e credenciais, também [`docs/provisionamento-clinicas.md`](docs/provisionamento-clinicas.md) e [`docs/segredos-clinicas.md`](docs/segredos-clinicas.md).
5. Inspecionar arquivos reais, testes, configurações e histórico antes de presumir uma implementação.
6. Em caso de conflito, reportar e pedir decisão antes de mudar escopo ou arquitetura. Segurança e privacidade prevalecem sobre conveniência. ADRs aceitas prevalecem sobre sugestões do planejamento.

## 3. Fluxo de trabalho por tarefa

1. Selecionar **uma** tarefa desbloqueada de `TASKS.md` e verificar as dependências no código, não só na marcação.
2. Descrever objetivo, arquivos previstos, impactos, riscos, testes e papéis de agente envolvidos antes de mudanças estruturais; aguardar aprovação.
3. Trabalhar em branch própria a partir da `main` atualizada (`feat/…`, `fix/…`, `docs/…`).
4. Implementar o menor incremento funcional, sem refatorações alheias ao objetivo.
5. Escrever ou atualizar testes e a documentação responsável na mesma mudança.
6. Executar os checks do escopo (§8); nunca declarar sucesso sem executar.
7. Atualizar `TASKS.md` com estado e evidências; não marcar `[x]` sem cumprir o aceite.
8. Entregar o handoff (§12). Commit, push e PR só quando o responsável pedir.

**Estados:** `[ ]` pendente, `[-]` em andamento ou parcial, `[x]` concluído, `[!]` bloqueado.

## 4. Arquitetura existente

| Camada | Implementação | Referência |
|---|---|---|
| Backend | Java 21, Spring Boot 4.1, Spring MVC REST, monólito modular `br.com.branlycrm` | ADR-001, ADR-017 |
| Frontend | Next.js 16, React 19, TypeScript strict, Tailwind, shadcn/ui em `frontend/` | ADR-012 |
| Banco | PostgreSQL 17 + pgvector; banco central e **um banco por clínica**; Flyway | ADR-014 |
| Autenticação | Spring Security, sessão de servidor, cookie HttpOnly, CSRF | ADR-015 |
| Processos | API, `migrate` (credencial de migração) e `provisioner` (cria clínicas), mesma imagem | ADR-016, ADR-018 |
| Ambiente | Docker Compose com Nginx em `localhost:8088`, API sob `/api/v1` | ADR-013 |

Módulos existentes: `identity`, `tenant`, `provisioning`, `config`. Novos módulos seguem a mesma estrutura (§8). Não criar microsserviços; Redis ou broker só com necessidade demonstrada. Dependências novas relevantes exigem justificativa e aprovação.

## 5. Segurança multi-tenant — regras inegociáveis

- Dados de negócio da clínica vivem **no banco da clínica**, criados por `db/tenant-migration`. O banco central guarda apenas registro, identidades, vínculos, provisionamento e, no futuro, planos e auditoria da plataforma.
- O tenant ativo vem da participação autorizada do usuário autenticado; o identificador enviado pelo cliente é só seletor. O sujeito sempre vem do principal, nunca do navegador.
- Acessar o banco da clínica somente por `TenantConnectionRepository.open(tenant, sujeito)` (ou pela persistência de clínica de CRM-015). Não há fallback para o banco central, tenant padrão ou variável de ambiente.
- Toda migração de clínica concede privilégios explícitos e mínimos a `${runtimeRole}`; nunca grants indiscriminados, nunca alterar migração já aplicada.
- A API usa só credenciais runtime. Senhas de administrador, migrador, provisionador e worker nunca chegam ao processo HTTP; o gravador de segredos fica no provisionador (o ArchUnit garante).
- Referências a usuários (responsável, autor) usam o `subject_id` global e são validadas contra participação ativa.
- Testar acesso indevido entre duas clínicas em cada módulo crítico: listagem, busca por ID, filtros, alterações, exportação e agregações.
- RBAC no servidor, menor privilégio e auditoria das operações relevantes. Nenhum bypass, nem para super admin, sem fluxo explícito, restrito e auditado.
- Nenhum segredo em código, logs, exemplos, testes ou commits. `.env` é ignorado; `.env.example` só tem exemplos.

## 6. Modelagem e integridade

- IDs UUID; timestamps em UTC, exibidos no fuso configurado da clínica.
- Dinheiro em centavos inteiros ou `NUMERIC` com precisão explícita; nunca ponto flutuante.
- Contatos e oportunidades são entidades distintas; um contato pode ter várias oportunidades.
- Oportunidade tem uma etapa ativa; ganha registra data e valor realizado; perdida exige motivo.
- Mudanças de etapa, responsável e encerramento geram eventos auditáveis.
- Constraints, índices e FKs no banco; unicidade sempre dentro da clínica (ex.: telefone normalizado), nunca global indevida.
- Flyway é a fonte do schema: migrações aditivas, plano de rollback descrito quando houver risco, sem operações destrutivas sem autorização.
- Decidir retenção, exclusão, exportação e anonimização antes de implementar remoção definitiva.

## 7. Privacidade e LGPD

- Minimizar dados pessoais; nada de prontuários, diagnósticos, imagens clínicas ou dados sensíveis sem necessidade e revisão específica.
- Nenhum dado pessoal real em fixtures, screenshots, testes ou prompts para agentes; usar `@example.test` e valores sintéticos.
- Criptografia em trânsito, controle de acesso, logs sanitizados (códigos estáveis em vez de mensagens SQL), retenção e backup com restauração testada.
- Registrar preferências de comunicação; campanhas e mensagens automáticas ficam fora do MVP.
- Identificar decisões jurídicas pendentes sem afirmar conformidade automática.

## 8. Padrão de desenvolvimento e qualidade

**Backend** ([padroes-backend.md](docs/padroes-backend.md)), por módulo: `controllers/`, `services/`, `repositories/`, `domain/`, `dto/request`, `dto/response`, `exceptions/` e, quando necessário, `security/`, `config/`, `secrets/`.
- Controllers `public final`, OpenAPI nos métodos e um `*ErrorHandler` por controller (`{"message"}`, 503 com `Retry-After` em falha de banco).
- Services com regras e transações, sem SQL, JDBC ou JPA, e devolvem DTOs `*View`.
- Repositories com SQL parametrizado, sem HTTP, DTOs ou services. Identificadores SQL só derivados do UUID, validados e citados.
- Domain em records com invariantes, sem Spring, e `toString` sem segredos.
- Exceções de negócio sem HTTP. Recusas não distinguem "não existe" de "sem acesso".
- Spotless com google-java-format; ArchUnit protege camadas, segredos e provisionamento.

**Testes:** unitários `*Test` (Surefire) e integração `*IT` (Failsafe) com PostgreSQL real via Testcontainers, imagem fixada por digest e container descartável por classe. Fluxos HTTP com cookies e CSRF reais (`support/Browser`). Isolamento com papéis sem superusuário (`TestPostgres.bootstrapRoles`). Teste mínimo de tenant: usuário de A não lista, lê, altera, remove nem agrega dados de B.

**Frontend:** TypeScript strict, tipos explícitos em contratos, nenhuma regra de negócio duplicada. Estados de carregamento, vazio, erro e sucesso; responsivo; acessível (labels, teclado, foco visível, erros compreensíveis). Nenhum segredo ou token em storage do navegador.

**Checks canônicos** (o que cada um prova: [qualidade.md](docs/qualidade.md)):
- Backend (`backend/`, JDK 21 e Docker): `.\mvnw.cmd -B -ntp clean verify` (Linux: `./mvnw -B -ntp clean verify`). Antes, `.\mvnw.cmd spotless:apply`.
- Frontend (`frontend/`): `npm ci`, `npm run lint`, `npx tsc --noEmit`, `npm run build`.
- Ambiente: `docker compose up -d --build --wait` na raiz.

Sem `TODO` silencioso em fluxos críticos; limitações e bloqueios ficam documentados.

## 9. Execução multiagente

O agente principal é o **coordenador**. Ele delega a papéis especializados quando o isolamento de contexto, a especialização ou trabalho realmente independente compensarem a coordenação. Não se paraleliza etapas dependentes. Protocolo completo e escolha de modelo: [`docs/agents/ORCHESTRATION.md`](docs/agents/ORCHESTRATION.md).

| Papel | Escreve código? | Responsabilidade | Definição |
|---|---|---|---|
| Coordenador (sessão principal) | Sim | Plano, ownership de arquivos, `TASKS.md`, Git, integração e checks finais | este arquivo |
| `architect` | Não | Fronteiras de módulos, ADRs, invariantes, riscos de tenancy | [ARCHITECT.md](docs/agents/ARCHITECT.md) |
| `backend` | Sim | Java/Spring, migrations, contratos REST, testes | [BACKEND.md](docs/agents/BACKEND.md) |
| `frontend` | Sim | Next.js, telas, estados, acessibilidade | [FRONTEND.md](docs/agents/FRONTEND.md) |
| `qa` | Só testes | Testes unitários, integração, isolamento A/B, concorrência | [QA.md](docs/agents/QA.md) |
| `security` | Não | Threat modeling: tenant, IDOR, sessão/CSRF, segredos, dados pessoais | [SECURITY.md](docs/agents/SECURITY.md) |
| `reviewer` | Não | Revisão do diff real por severidade, antes de entregar | [REVIEWER.md](docs/agents/REVIEWER.md) |

No Claude Code, os papéis estão em `.claude/agents/`. No Codex, use os mesmos documentos de `docs/agents/` como instrução de papel.

**Regras:**
- Um escritor por arquivo. O coordenador define quais arquivos cada implementador pode tocar. Lockfiles, `pom.xml`, `compose.yaml`, `.env.example`, `TASKS.md` e Git pertencem ao coordenador.
- Revisores (`architect`, `security`, `reviewer`) são read-only. `qa` só cria ou altera testes.
- Cada delegação traz objetivo, contexto mínimo, arquivos permitidos, dependências, critérios de aceite, checks e formato de retorno (arquivos alterados, evidência, dúvidas, limitações).
- O coordenador confere o diff e roda os checks antes de aceitar o resultado de um subagente. O relato do subagente não é prova.
- Tenancy, segurança, migrações, concorrência, autenticação e dados pessoais usam o modelo de maior capacidade e passam por `security` ou `reviewer`.
- Em mudanças que tocam tenant, credenciais ou autenticação, a revisão de segurança é obrigatória antes de concluir.

## 10. Aprovação humana obrigatória

Antes de:
- mudar stack ou arquitetura;
- adicionar serviços pagos ou dependências relevantes;
- criar integração externa;
- executar migrations em produção;
- excluir dados;
- modificar autorização ou tenancy de forma estrutural;
- ampliar o escopo do MVP;
- acessar dados reais;
- alterar planos ou cobrança;
- fazer commit, push, abrir PR ou mergear.

## 11. Definição de pronto (DoD)

Concluída somente quando:
- (a) o aceite observável foi cumprido;
- (b) tenant e RBAC foram validados com teste A/B quando aplicável;
- (c) os testes foram escritos e executados;
- (d) `verify`, lint, typecheck e build do escopo foram executados, ou o impedimento está documentado;
- (e) a documentação e o `TASKS.md` foram atualizados;
- (f) a revisão de segurança foi feita quando exigida (§9);
- (g) não há segredo nem dado pessoal real incluído.

## 12. Modelo de handoff

```md
### Tarefa: CRM-XXX
Status: concluída | parcial | bloqueada
Resumo: ...
Agentes envolvidos: coordenador, backend, qa, security...
Arquivos alterados: ...
Migrations/contratos: ...
Testes executados: comando → resultado
Segurança/tenant: verificações realizadas
Pendências/riscos: ...
Próxima tarefa sugerida: CRM-YYY
```

## 13. Prompt inicial recomendado

> Leia `AGENTS.md`, `docs/planejamento-crm-clinicas-estetica.md` e `TASKS.md`. Inspecione o repositório e confirme o estado das tarefas em andamento. Proponha a próxima tarefa desbloqueada com plano, arquivos, riscos, testes e papéis de agente. Não altere a arquitetura nem amplie o escopo sem aprovação. Implemente somente a tarefa aprovada e registre evidências no backlog.
