# Instruções para agentes — Branly CRM

Codex lê este arquivo diretamente; Claude Code o importa por `CLAUDE.md`.

## Fonte da verdade

Leia [docs/README.md](docs/README.md), [docs/escopo.md](docs/escopo.md) e as ADRs em [docs/adr/decisoes.md](docs/adr/decisoes.md) antes de alterar código. Não amplie o escopo do MVP sem aprovação; registre hipóteses e peça decisão quando houver impacto de segurança, produto ou custo. Atualize o documento responsável na mesma mudança de comportamento, comando, schema ou fronteira.

## Comandos canônicos

- Backend (`backend/`, JDK 21 e Docker): `.\mvnw.cmd -B -ntp clean verify` (Linux: `./mvnw -B -ntp clean verify`). Formatação, unitários, ArchUnit e integração PostgreSQL via Testcontainers.
- Formatação do backend: `.\mvnw.cmd spotless:apply`.
- Frontend (`frontend/`): `npm ci`, `npm run lint`, `npx tsc --noEmit`, `npm run build`.
- Ambiente completo: `docker compose up -d --build` na raiz (Nginx em `localhost:8088`).

O que cada verificação prova: [docs/qualidade.md](docs/qualidade.md).

## Engenharia

- Backend segue [docs/padroes-backend.md](docs/padroes-backend.md) e a ADR-017: camadas explícitas dentro dos módulos, sem SQL/JDBC/JPA em controllers ou services.
- Monólito modular Java 21/Spring Boot, PostgreSQL com pgvector, Flyway. Frontend Next.js/TypeScript sem regras de negócio duplicadas.
- Isolamento entre clínicas é requisito crítico: banco próprio por clínica (ADR-014), tenant derivado de participação autorizada, nunca apenas de parâmetro do cliente, e sem fallback para o banco central.
- A API usa somente a credencial runtime (ADR-016); nunca entregar a ela senhas de administrador, migrador ou provisionador.
- Não expor segredos, hashes, tokens ou dados pessoais em logs e respostas. Testes usam dados sintéticos (`@example.test`).
- Não executar migrações destrutivas, alterar migrações aplicadas, apagar dados, fazer deploy ou criar integrações reais sem aprovação.

## Conclusão de tarefa

Código, testes relevantes, documentação atualizada e revisão de isolamento entre clínicas. Rode os checks do escopo alterado; nunca afirme que algo rodou sem executar. Se um passo não for possível, registre o motivo.
