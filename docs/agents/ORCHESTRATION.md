# Orquestração multiagente

O agente principal coordena. Ele delega aos papéis `architect`, `backend`, `frontend`, `qa`, `security` e `reviewer` quando o cliente suportar subagentes. Não presuma execução paralela automática: confirme o comportamento na sessão. Regras de ownership e aprovação: [AGENTS.md §9–§10](../../AGENTS.md).

## Protocolo por tarefa

1. **Coordenador:** lê a tarefa em `TASKS.md`, confere as dependências no código e monta o plano com ownership de arquivos.
2. **Architect** (quando houver módulo novo, migração, tenancy ou mudança de fronteira): valida o desenho contra as ADRs e lista invariantes e riscos.
3. **Aprovação humana** do plano.
4. **Implementadores** (`backend`, `frontend`) executam fatias independentes, cada um nos seus arquivos. Contrato de API primeiro: o backend define DTOs e rotas, e o frontend só consome contrato aprovado.
5. **QA** escreve ou completa os testes de aceite, de isolamento A/B e de concorrência e roda os checks.
6. **Security** revisa obrigatoriamente quando a mudança toca tenant, autenticação, sessão/CSRF, credenciais, segredos ou dados pessoais.
7. **Reviewer** revisa o diff real por severidade.
8. **Coordenador:** corrige ou delega as correções, roda os checks finais, atualiza `TASKS.md` e entrega o handoff.

Passos sem dependência entre si podem rodar em paralelo, por exemplo backend e frontend depois do contrato aprovado, ou `security` e `reviewer` sobre o mesmo diff. Passos dependentes, nunca.

## Exemplo — CRM-021 (API de contatos)

| Etapa | Papel | Arquivos permitidos |
|---|---|---|
| Desenho | architect | nenhum (read-only) |
| Migração + camadas | backend | `db/tenant-migration/V<n>__contacts.sql`, `contacts/**` |
| Testes A/B e filtros | qa | `src/test/**/contacts/**` |
| Revisão | security, reviewer | nenhum (read-only) |
| `TASKS.md`, `pom.xml`, Git | coordenador | — |

## Seleção de modelo

Escolha o modelo mais leve capaz de cumprir os critérios de aceite, preservando o provedor e a autenticação já autorizados. Não configure chave, proxy ou cobrança nova para delegar.

| Tarefa | Capacidade |
|---|---|
| Inventário, buscas, leitura de logs, mudanças mecânicas pequenas | Leve, com saída verificável |
| Implementação delimitada, telas, revisão de comportamento | Intermediária |
| Arquitetura, segurança, tenant, migrações, concorrência, autenticação, dados pessoais, diagnóstico ambíguo | Maior desde o início |

Se a qualidade for insuficiente, aumente a capacidade ou o contexto em vez de repetir tentativas idênticas, com limite finito.

## Delegação

Cada delegação informa:
- objetivo;
- tarefa (`CRM-XXX`);
- contexto mínimo (arquivos e ADRs);
- arquivos permitidos;
- recursos compartilhados que não podem ser tocados;
- critérios de aceite;
- checks a executar;
- formato de retorno.

```md
Papel: backend
Tarefa: CRM-021 — API CRUD de contatos
Objetivo: ...
Contexto: AGENTS.md §5 e §8, ADR-014, ADR-018, docs/padroes-backend.md
Arquivos permitidos: ...
Não tocar: pom.xml, compose.yaml, TASKS.md, migrações já aplicadas
Aceite: ...
Checks: ./mvnw -B -ntp clean verify
Retorno: arquivos alterados, comandos e resultados, dúvidas, limitações
```

O coordenador nunca aceita um resultado só pelo relato: confere o diff e roda os checks. Revisões terminam quando os achados relevantes estão resolvidos ou com disposição explícita e os checks afetados passam.

## Continuidade

Trabalho inacabado entre sessões vira um handoff curto na própria tarefa em `TASKS.md`, com objetivo, estado verificado, checks, pendências e próxima ação. Ao retomar, confira branch, arquivos e processos antes de confiar no handoff.
