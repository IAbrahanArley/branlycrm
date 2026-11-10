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
| `page` | Começa em 1; padrão 1 |
| `pageSize` | Padrão 25; de 1 a 100 |

A posição mais funda alcançável é a linha 10.000 (`(page - 1) × pageSize ≤ 10000`), porque o OFFSET faz o PostgreSQL percorrer todas as linhas puladas. Listas maiores são estreitadas por filtros ou, no módulo que precisar, por paginação por chave.

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

A validação fica nos records de domínio (construtor ou `of(...)`), não em anotações de Bean Validation (ADR-023). Isso cobre regras de Unicode, normalização e limites que `@Size` não expressa.

- Entrada inválida lança `common/exceptions/InvalidInputException` com os nomes dos campos.
- JSON malformado vira `INVALID_INPUT` com o campo `body`; parâmetro de caminho com tipo errado vira o nome do parâmetro.
- `IllegalArgumentException` comum **não** vira 400: indica bug interno e deve aparecer como erro.

## Erros

Todo erro tem este corpo, inclusive os gerados pelos filtros de segurança antes do Spring MVC, os de rota inexistente e os erros internos:

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
| `SERVICE_UNAVAILABLE` | 503 | Banco indisponível, espera de lock esgotada ou banco da clínica com migração pendente; respeitar `Retry-After` | Handlers e `ClinicDataErrorHandler` |
| `INVITATION_INVALID` | 404 | Convite desconhecido, vencido, usado, revogado ou cuja autoridade de quem emitiu caiu; sempre a mesma resposta | Convites |
| `INVITATION_LOGIN_REQUIRED` | 409 | Já existe conta verificada para o e-mail: entrar com ela e aceitar de novo | Convites |
| `INVITATION_ACCOUNT_MISMATCH` | 409 | A sessão é de outra conta, não do e-mail convidado | Convites |
| `MEMBER_ALREADY_ACTIVE` | 409 | O e-mail já é de um membro ativo da clínica | Convites |
| `INVITATION_NOT_FOUND` | 404 | Convite pendente inexistente na clínica selecionada, inclusive de outra clínica | Convites de equipe |
| `INVITATION_FORBIDDEN` | 403 | O papel de quem age não alcança o papel do convite | Convites de equipe |
| `INVITATION_SEND_LIMIT` | 429 | Limite de e-mails de convite: 5 por convite, 1 por minuto por convite e 100 por clínica a cada 24 horas | Convites de equipe |
| `AGENCY_NOT_FOUND` | 404 | Agência inexistente, suspensa ou que o usuário não administra; sempre a mesma resposta | Agências |
| `CLINIC_NOT_FOUND` | 404 | Clínica fora da carteira da agência: de outra agência, direta ou inexistente | Carteira da agência |
| `IDEMPOTENCY_KEY_REUSED` | 409 | O mesmo `requestId` já criou uma clínica com outros dados | Carteira da agência |
| `AGENCY_PENDING_CLINIC_LIMIT` | 409 | Clínicas demais em preparação na agência (padrão: 20) | Carteira da agência |
| `AGENCY_CLINIC_HAS_OWNER` | 409 | A clínica já teve gestor; convites passam a ser da própria clínica | Carteira da agência |
| `MFA_REQUIRED` | 401 | Sessão logada pela senha, mas o segundo fator ainda não foi verificado | `MfaGateFilter` |
| `MFA_ENROLLMENT_REQUIRED` | 403 | Dono de clínica ou admin de agência sem segundo fator cadastrado | `MfaGateFilter` |
| `MFA_CODE_INVALID` | 400 | Código errado, já usado ou de recuperação inválido; sempre a mesma resposta | Segundo fator |
| `MFA_LOCKED` | 429 | Falhas demais na conta (10 em sequência); o bloqueio dobra a cada repetição, até 24 h; respeitar `Retry-After` | Segundo fator |
| `MFA_SETUP_EXPIRED` | 409 | Cadastro sem segredo pendente válido nesta sessão; refazer o setup | Segundo fator |
| `MFA_MANDATORY` | 403 | Quem é obrigado a ter o fator não pode desativá-lo | Segundo fator |
| `MFA_NOT_ENROLLED` | 409 | Operação exige fator cadastrado | Segundo fator |
| `MFA_ALREADY_COMPLETE` | 409 | Verificação numa sessão já verificada | Segundo fator |
| `CONTACT_NOT_FOUND` | 404 | Contato inexistente na clínica selecionada, inclusive de outra clínica | Contatos |
| `CONTACT_DUPLICATE_PHONE` | 409 | O número já é de outro contato da clínica, inclusive arquivado; o corpo traz também `contactId` (nulo se desconhecido) | Contatos |
| `CONTACT_STALE` | 409 | O contato mudou desde a leitura (`version`); recarregar e reaplicar | Contatos |
| `CONTACT_ARCHIVED` | 409 | Edição ou vínculo de tag em contato arquivado; desarquivar antes | Contatos |
| `CONSENT_REFUSED` | 409 | A regra de consentimento recusou a mudança | Contatos |
| `TAG_NOT_FOUND` | 404 | Tag inexistente na clínica selecionada, inclusive de outra clínica | Tags |
| `TAG_DUPLICATE` | 409 | Já existe tag com o mesmo nome, sem diferenciar maiúsculas | Tags |
| `TAG_LIMIT` | 409 | A clínica já tem 500 tags; o catálogo é devolvido inteiro, sem paginação | Tags |
| `LEAD_NOT_FOUND` | 404 | Lead inexistente na clínica selecionada, inclusive de outra clínica | Leads |
| `LEAD_ALREADY_OPEN` | 409 | O contato já tem um lead aberto; o corpo traz também `leadId` | Leads |
| `LEAD_STALE` | 409 | O lead mudou desde a leitura (`version`); recarregar e reaplicar | Leads |
| `LEAD_CLOSED` | 409 | O lead já foi ganho ou perdido; abrir um lead novo | Leads |
| `LEAD_CONTACT_UNAVAILABLE` | 409 | O contato não existe nesta clínica ou está arquivado (mesma resposta nos dois casos) | Leads |
| `LEAD_STAGE_NOT_ALLOWED` | 409 | Etapa inexistente, arquivada, ganha (só pela venda) ou perdida (só com motivo) | Leads |
| `LEAD_LOSS_REASON_NOT_FOUND` | 409 | Motivo de perda inexistente ou arquivado | Leads |
| `LEAD_RESPONSIBLE_INVALID` | 409 | O responsável não é membro ativo que trabalha leads | Leads |
| `PROCEDURE_NOT_FOUND` / `PROFESSIONAL_NOT_FOUND` | 404 | Item inexistente nesta clínica (mesma resposta para o de outra clínica) | Catálogo |
| `PROCEDURE_DUPLICATE` / `PROFESSIONAL_DUPLICATE` | 409 | Já existe item com o mesmo nome, sem diferenciar maiúsculas, inclusive inativo | Catálogo |
| `PROCEDURE_STALE` / `PROFESSIONAL_STALE` | 409 | O item mudou desde a leitura (`version`) | Catálogo |
| `PROCEDURE_LIMIT` / `PROFESSIONAL_LIMIT` | 409 | Limite de 300 procedimentos ou 100 profissionais por clínica | Catálogo |
| `STATE_CONFLICT` | 409 | O banco recusou uma mudança de estado ou de campo imutável (BRL02), ou uma escrita concorrente no banco da clínica | Todos os handlers |
| `NOT_FOUND` | 404 | Rota inexistente (usuário autenticado; anônimo recebe 401) | `GlobalErrorHandler` |
| `METHOD_NOT_ALLOWED` | 405 | Método HTTP não suportado pela rota | `GlobalErrorHandler` |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Corpo com tipo diferente de JSON | `GlobalErrorHandler` |
| `INTERNAL_ERROR` | 500 | Bug; registrado no log do servidor com stack trace, nunca na resposta | `GlobalErrorHandler` |

**Na implementação:**
- Códigos gerais ficam em `common/web/ApiErrors`; códigos de um módulo ficam no `*ErrorHandler` dele.
- Cada controller mantém o seu `*ErrorHandler` (ADR-017), com `@Order(GlobalErrorHandler.MODULE_ORDER)` e usando `ApiErrors` para montar o corpo.
- `common/web/GlobalErrorHandler` é o fallback de menor prioridade para o que nenhum módulo trata. Exceções do Spring Security são relançadas para virar 401/403 no filtro.
- Recursos identificados por ID que o usuário não pode ver respondem 404, igual a inexistentes.

## Documentação OpenAPI

Cada operação declara `@Operation` e, para cada erro próprio, um `@ApiResponse` com o código, por exemplo `"INVALID_INPUT: page, pageSize, role or active"`. Os erros comuns (401 `AUTHENTICATION_REQUIRED`/`SESSION_REVOKED`, 403 `CSRF_INVALID` em mutações, 500 e 503) e o esquema `ApiError` em toda resposta de erro são adicionados por `OpenApiConfiguration.standardErrorResponses`. Parâmetros de paginação e filtros têm `@Parameter` com a regra. As rotas de login e logout, que são filtros, ficam em `OpenApiConfiguration`.
