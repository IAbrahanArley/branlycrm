# Convenções de código do backend

Adotadas em 05/11/2026 (ADR-017), seguindo o padrão do projeto Smart Commerce do mesmo responsável.

## Organização

Monólito modular, com camadas dentro de cada funcionalidade. Criar apenas os pacotes necessários:

```text
br.com.branlycrm/
  BranlyCrmApplication.java
  config/                  segurança, OpenAPI e migrador central
  identity/ | tenant/
    controllers/           rotas e tradução HTTP (*Controller, *ErrorHandler)
    services/              casos de uso, regras e transações
    repositories/          SQL/JDBC, mapeamento e conexões
    domain/                valores e invariantes, sem Spring
    dto/request/           contratos de entrada
    dto/response/          contratos de saída (*View)
    exceptions/            resultados de negócio, sem HTTP
    security/              filtros e principal (identity)
```

Não criar módulos futuros, classes base genéricas ou interfaces sem uso demonstrado. Módulos de negócio (contatos, agenda, conversas, conhecimento) seguirão a mesma estrutura quando forem implementados.

## Responsabilidades

- **Controllers:** classe `public final` com `@RestController`, rotas, parsing, identidade autenticada e respostas. Documentação OpenAPI (`@Tag`, `@Operation`, `@ApiResponse`, `@SecurityRequirement`) fica no próprio método. Sem SQL nem acesso direto a repositórios.
- **ErrorHandler:** um `@RestControllerAdvice(assignableTypes = ...)` por controller traduz exceções do módulo em status e `{"message": ...}`. Falha de banco vira 503 com `Retry-After`, sem detalhes SQL ou stack trace.
- **Services:** casos de uso e fronteiras transacionais (`@Transactional`). Dependências por construtor; sem `JdbcTemplate`, `java.sql` ou JPA. Devolvem DTOs de resposta, nunca linhas do banco.
- **Repositories:** consultas parametrizadas, mapeamento e conexões. Não chamam services ou controllers e não conhecem HTTP nem DTOs.
- **Domain:** records e valores com invariantes no construtor; nenhuma dependência de Spring ou de outras camadas. `toString` não expõe hashes, e-mails ou segredos.
- **Exceptions:** resultado de negócio (`TenantAccessException`); a tradução HTTP pertence ao ErrorHandler. Recusas não distinguem "não existe" de "sem acesso".

Login e logout continuam nos filtros nativos do Spring Security (ADR-015) e são documentados em `OpenApiConfiguration`, sem controller fictício.

## Persistência e segurança

Identidade e registro de tenants usam JDBC em repositórios, porque as consultas de autorização precisam ser explícitas. JPA permanece configurado com `ddl-auto: validate` e `open-in-view: false`, sem entidades de negócio no banco central. Flyway é a fonte do schema; não alterar migrações já aplicadas.

O sujeito vem do principal autenticado; o tenant enviado pelo cliente é somente seletor e é revalidado em cada requisição. `TenantConnectionRepository.open(tenant, sujeito)` verifica participação ativa antes de ler o segredo e confere `tenant_identity` depois de conectar, sem fallback.

## Formatação e testes

- **Formatação:** Spotless com google-java-format 1.28.0, verificado na fase `validate`. Rodar `.\mvnw.cmd spotless:apply` antes de concluir.
- **Unitários (`*Test`, Surefire):** regras de domínio, services com repositórios simulados e filtros.
- **Arquitetura:** `ArchitectureTest` (ArchUnit) impede SQL/JPA em controllers e services, HTTP em services e repositories, e dependências da camada domain.
- **Integração (`*IT`, Failsafe):** PostgreSQL 17 com pgvector via Testcontainers, imagem fixada por digest, um container descartável por classe. Testes HTTP usam `support/Browser` (cookies e CSRF reais). Dados sempre sintéticos (`@example.test`).

Comandos e o que cada um prova estão em [qualidade.md](qualidade.md).
