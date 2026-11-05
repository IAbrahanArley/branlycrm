# Convenções da API REST

Definidas em 05/11/2026 (CRM-004). Valem para todo endpoint novo. O exemplo de referência é `GET /v1/clinic/team`.

## Rotas e versionamento

- Base pública `/api` (contexto do Spring no Docker e na hospedagem) seguida da versão: `/api/v1/...`.
- Recursos no plural e em inglês (`/v1/clinic/team`, futuramente `/v1/contacts`). Recursos da clínica usam sempre a clínica selecionada na sessão, nunca um identificador de clínica na URL.
- **Mudanças:** adicionar campos ou rotas não quebra o contrato. Remover ou renomear campos, mudar tipos ou mudar o significado de um código de erro exige `/v2` ou um período de convivência documentado.
- **Métodos:**

  | Método | Uso | Resposta |
  |---|---|---|
  | `GET` | Leitura | 200 |
  | `POST` | Criação | 201 com o recurso e `Location` |
  | `PUT` | Substituição ou ação idempotente sobre um campo | 200 com o recurso |
  | `DELETE` | Desativação ou remoção | 204 |

  Toda mutação exige o CSRF (ADR-015).

## Paginação

Toda lista que cresce com o uso é paginada no servidor.

| Parâmetro | Regra |
|---|---|
| `page` | Começa em 1; padrão 1; máximo 10000 |
| `pageSize` | Padrão 25; de 1 a 100 |

Resposta:

```json
{ "items": [ ... ], "page": 1, "pageSize": 25, "total": 42 }
```

- `total` conta todos os itens que atendem aos filtros.
- Uma página além do fim devolve `items` vazio com o `total` correto.
- A ordenação é estável (sempre termina num campo único), então itens não se repetem nem somem entre páginas.
- Implementação: `common/domain/PageQuery`, `common/domain/PageResult` e `common/dto/response/PageView`.

## Filtros

- **Formato:** parâmetros de query nomeados pelo campo (`role`, `active`, futuramente `source`, `assignedTo`…), combinados com E.
- **Ausência:** um filtro ausente ou vazio não filtra.
- **Validação:** os valores são validados num record de consulta do domínio (ex.: `TeamQuery.of`). O SQL usa trechos fixos e parâmetros, nunca o texto recebido.
- **Inválidos:** valores fora do conjunto aceito retornam 400 `INVALID_INPUT` com o nome do campo.
- **Desconhecidos:** parâmetros que o endpoint não declara são ignorados.
- **Ordenação configurável:** quando houver, usa `sort` com valores de uma lista fechada por endpoint.

## Validação de entrada

Segue o padrão do Smart Commerce: a validação fica nos records de domínio (construtor ou `of(...)`), não em anotações de Bean Validation. Isso cobre regras de Unicode, normalização e limites que `@Size` não expressa.

- Entrada inválida lança `common/exceptions/InvalidInputException` com os nomes dos campos.
- JSON malformado vira `INVALID_INPUT` com o campo `body`; parâmetro de caminho com tipo errado vira o nome do parâmetro.
- `IllegalArgumentException` comum **não** vira 400: indica bug interno e deve aparecer como erro.

## Erros

Todo erro, inclusive os gerados pelos filtros de segurança antes do Spring MVC, tem este corpo:

```json
{ "code": "INVALID_INPUT", "message": "Dados inválidos.", "fields": ["pageSize"] }
```

- `code` é contrato estável: o frontend decide por ele.
- `message` é texto em português para exibição e pode mudar.
- `fields` só é preenchido em `INVALID_INPUT`, com o nome do primeiro campo inválido encontrado, nunca o valor recebido. A validação de formato acontece antes da autorização, então um papel sem acesso pode receber 400 em vez de 403. Isso não revela dados.
- Nenhum erro carrega SQL, stack trace, nome de exceção, segredo ou dado pessoal.

| Código | Status | Quando | Origem |
|---|---|---|---|
| `AUTHENTICATION_REQUIRED` | 401 | Sem sessão | Spring Security |
| `INVALID_CREDENTIALS` | 401 | Login recusado; mesma resposta para senha errada, conta inexistente, inativa ou não verificada | Filtro de login |
| `SESSION_REVOKED` | 401 | Conta desativada ou credenciais alteradas desde o login | `SessionValidationFilter` |
| `CSRF_INVALID` | 403 | Mutação sem token CSRF válido; buscar novo em `GET /v1/auth/csrf` | Spring Security |
| `ACCESS_DENIED` | 403 | Outra recusa de segurança | Spring Security |
| `CLINIC_ACCESS_DENIED` | 403 | Sem participação ativa, clínica inativa ou papel sem a permissão | Serviços e filtro |
| `MEMBER_CHANGE_FORBIDDEN` | 403 | Papel não pode alterar este membro, ou alteração em si mesmo | Equipe |
| `MEMBER_NOT_FOUND` | 404 | Alvo não é membro ativo da clínica selecionada | Equipe |
| `CLINIC_NOT_SELECTED` | 409 | Rota de clínica sem seleção na sessão | Controllers |
| `LAST_OWNER` | 409 | Alteração deixaria a clínica sem proprietário | Equipe |
| `INVALID_INPUT` | 400 | Entrada inválida (`fields` diz quais) | Handlers |
| `RATE_LIMITED` | 429 | Excesso de tentativas de login; respeitar `Retry-After` | `LoginRateLimitFilter` |
| `SERVICE_UNAVAILABLE` | 503 | Banco indisponível ou espera de lock esgotada; respeitar `Retry-After` | Handlers |

**Na implementação:**
- Códigos gerais ficam em `common/controllers/ApiErrors`; códigos de um módulo ficam no `*ErrorHandler` dele.
- Cada controller mantém o seu `*ErrorHandler` (ADR-017), usando `ApiErrors` para montar o corpo.
- Recursos identificados por ID que o usuário não pode ver respondem 404, igual a inexistentes.

## Documentação OpenAPI

Cada operação declara `@Operation` e, para cada erro possível, um `@ApiResponse` com o código, por exemplo `"INVALID_INPUT: page, pageSize, role or active"`. Parâmetros de paginação e filtros têm `@Parameter` com a regra. As rotas de login e logout, que são filtros, ficam em `OpenApiConfiguration`.
