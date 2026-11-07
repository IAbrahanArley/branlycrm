# Documentação — Branly CRM

Consolidado em 05/11/2026. Esta documentação registra decisões e requisitos; não comprova implementação.

## Leitura

Comece por aqui:

- [Instruções para agentes (inclui multiagentes)](../AGENTS.md)
- [Planejamento do produto — fonte do escopo do MVP](planejamento-produto.md)
- [Documento de requisitos do cliente v1.1 (PDF)](requisitos/requisitos-plataforma-clinicas-v1.1.pdf)
- [Backlog executável e estado verificado](../TASKS.md)
- [Papéis e orquestração dos agentes](agents/ORCHESTRATION.md)
- [Papéis e permissões das clínicas](permissoes.md)
- [Convenções da API: erros, paginação, filtros](api-convencoes.md)

Referência:

- [Escopo funcional — histórico, substituído pelas ADR-019 e ADR-024](escopo.md)
- [Decisões arquiteturais — ADR-001 a ADR-028](adr/decisoes.md)
- [Cenários e critérios de aceitação](cenarios-e-aceitacao.md)
- [Backend MVC e dependências](backend-mvc.md)
- [Convenções de código do backend](padroes-backend.md)
- [Qualidade e comandos de verificação](qualidade.md)
- [Entregas e pendências — histórico, substituído por TASKS.md](entregas-e-pendencias.md)
- [Estrutura e situação do projeto](projeto.md)

SC significa cenário; AC significa critério de aceitação. Propostas e detalhes ainda não confirmados estão identificados como pendências. Regras propostas nos ACs não constituem aprovação comercial automática.

Os documentos originais foram preservados e complementados. ADRs históricas mantêm suas datas; decisões novas estão datadas separadamente. Mudanças futuras devem registrar qual decisão substituem.

- [Ambiente Docker e conexões](docker.md)

- [Multitenancy: banco por clínica](multitenancy.md)

- [Identidade e Spring Security](autenticacao.md)

- [Plano da próxima entrega: onboarding e provisionamento](plano-onboarding.md)

- [Separação de credenciais PostgreSQL](credenciais-postgres.md)

- [Próxima etapa: credenciais por clínica e isolamento](plano-isolamento-clinicas.md)

- [Segredos das clínicas](segredos-clinicas.md)

- [Provisionamento de clínicas e pools](provisionamento-clinicas.md)
