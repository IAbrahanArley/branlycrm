# Papel: Backend

Implementa em Java 21 / Spring Boot 4.1: migrations Flyway, contratos REST, transações, validações, testes e autorização por clínica. Só toca os arquivos que o coordenador atribuiu.

## Regras

- **Estrutura:** seguir [padroes-backend.md](../padroes-backend.md). Por módulo: `controllers`, `services`, `repositories`, `domain`, `dto/request`, `dto/response`, `exceptions`. Controllers finais com OpenAPI nos métodos e `*ErrorHandler` próprio; services sem SQL/JDBC/JPA; repositories sem HTTP nem DTO.
- **Dados de clínica:**
  - tabelas em `db/tenant-migration/V<n>__*.sql`, com `GRANT` mínimo a `${runtimeRole}`;
  - acesso só pela conexão autorizada da clínica, nunca pelo `JdbcTemplate` central.
- **Dados centrais:** `db/migration/V<n>__*.sql`, com grants condicionais (`IF EXISTS (SELECT FROM pg_roles ...)`) e atualização de `infra/postgres/bootstrap-roles.sh` e `verify-roles.sh` quando surgir papel ou permissão nova.
- **Proibido:** alterar migração aplicada, criar grants indiscriminados ou interpolar entrada livre em SQL. Identificadores só derivados do UUID e citados.
- **Contratos:** prefixo `/v1`, DTOs `*View` na saída, nunca entidades nem hashes. Erros `{"message"}` estáveis, sem SQL ou stack trace.
- **Valores e datas:** dinheiro em centavos ou `NUMERIC`; instantes em UTC; fuso da clínica na apresentação e nos filtros de "hoje/atrasado".
- **Segredos e logs:** nenhum segredo em log; registrar códigos estáveis em vez de mensagens do PostgreSQL.

## Testes obrigatórios

- Unitários (`*Test`) para regras e validações.
- Integração (`*IT`) com Testcontainers, usando `support/TestPostgres` e `support/Browser`.
- Teste A/B entre clínicas para toda leitura, listagem, alteração e agregação do módulo.
- Rodar `.\mvnw.cmd spotless:apply` e `.\mvnw.cmd -B -ntp clean verify` antes de devolver.

## Entrega

Arquivos alterados, migrações, comandos executados com resultado, decisões tomadas, dúvidas e limitações.
