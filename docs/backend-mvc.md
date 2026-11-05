# Backend MVC e inicialização

Data: 05/11/2026.

## Padrão de organização

Monólito modular por funcionalidade. Dentro de cada módulo: controller, service, repository, model e DTOs. MVC REST: Spring MVC expõe JSON; a interface visual é o Next.js. Não utilizar templates de servidor para as telas.

Controller trata HTTP e delega ao service; service contém regras e limites transacionais; repository trata persistência; DTOs definem contratos sem expor entidades JPA. Autorização não depende apenas da controller: services devem preservar permissões e isolamento também para workers e IA. Exceções HTTP padronizadas por RestControllerAdvice.

## Controllers

A ADR-017 substituiu a interface ControllerDoc no mesmo arquivo (ADR-010). Controllers são classes finais com mapeamentos e anotações OpenAPI nos próprios métodos, delegam a services e têm um ErrorHandler próprio. Convenções completas em [padroes-backend.md](padroes-backend.md).

- Documentar respostas de sucesso e erros reais, incluindo autenticação, autorização, validação, recurso ausente e conflito quando aplicáveis.
- DTOs e services ficam em arquivos próprios.

## Spring Initializr

Recomendação: Maven, Java 21, empacotamento Jar e versão estável de Spring Boot oferecida no gerador, sem SNAPSHOT ou milestone. O padrão MVC não exige mudar a arquitetura modular.

Selecionar:

| Dependência | Uso |
|---|---|
| Spring Web | API REST com Spring MVC |
| Spring Data JPA | Persistência |
| PostgreSQL Driver | Driver do banco |
| Validation | Validação de entrada |
| Spring Security | Autenticação e autorização; exige configuração própria |
| Flyway Migration | Migrações versionadas |
| Spring Boot Actuator | Saúde e métricas |
| Java Mail Sender | Convites e recuperação por e-mail (removido até o fluxo de e-mail existir; ADR-017) |

Opcionais: Docker Compose Support para ambiente local; Testcontainers para testes com PostgreSQL; DevTools apenas no desenvolvimento. Lombok não é obrigatório.

Após gerar: adicionar org.springdoc:springdoc-openapi-starter-webmvc-ui. Spring Boot 4 usa springdoc 3.x; Boot 3 usa springdoc 2.x. Fixar uma versão compatível conforme matriz oficial. Conferir também presença de org.flywaydb:flyway-database-postgresql e org.postgresql:postgresql; o Initializr pode incluir os módulos necessários automaticamente.

Não selecionar WebFlux, Spring Data REST, Thymeleaf, Redis ou broker como requisitos iniciais. Spring AI, SDK de armazenamento, autenticação OAuth2 e biblioteca de pagamentos dependem das decisões dos respectivos provedores. pgvector é uma extensão do PostgreSQL e não uma opção obrigatória do Initializr.

## Fontes

- https://start.spring.io/
- https://springdoc.org/
- https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-requestmapping.html
