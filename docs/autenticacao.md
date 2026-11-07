# Identidade, sessões e seleção de clínica

Implementação inicial em 05/11/2026. ADR-015.

## Camadas

Organização conforme ADR-017 e [padroes-backend.md](padroes-backend.md). identity/repositories/UserAccountRepository e tenant/repositories/TenantRegistryRepository concentram SQL. identity/services/IdentityService valida estado da identidade; tenant/services/TenantAccessService valida participação; identity/services/SessionService coordena seleção e devolve DTOs (IdentityView, ClinicView, ClinicSelectionView, CsrfView). SessionController é final, com OpenAPI nos métodos; SessionErrorHandler traduz TenantAccessException em 403. Filtros e principal ficam em identity/security; a cadeia de segurança em config/SecurityConfiguration.

Banco central: user_accounts com UUID permanente, e-mail normalizado único, hash bcrypt, estado ativo, verificação de e-mail e versão de autenticação. tenant_memberships referencia a identidade global com chave estrangeira. Papéis são por clínica: OWNER, MANAGER, ATTENDANT, VIEWER (ADR-025, V7; antes, os cinco da ADR-020 e V6). Não são autoridades globais na sessão; o papel é relido a cada autorização, então uma mudança vale na requisição seguinte.

## Protocolo HTTP

Prefixo externo no Docker: /api/v1/auth.

1. GET /csrf: retorna token, headerName e parameterName. Preservar cookie.
2. POST /login: application/x-www-form-urlencoded, campos email e password, header CSRF e cookie. Retorna 204 ou 401; não há redirecionamento para página de login.
3. GET /csrf novamente após login, pois o token anterior é invalidado.
4. GET /me: subjectId e email, sem credenciais. GET /clinics: participações ativas em clínicas ativas.
5. PUT /clinic/{tenantId}: exige CSRF e participação ativa; armazena UUID na sessão e retorna tenantId. O UUID do sujeito vem do principal autenticado, não do navegador.
6. POST /logout: exige CSRF, invalida sessão e remove cookie; retorna 204. Obter novo token antes de novo login.
7. GET /clinic: clínica selecionada, papel e escopo de cada permissão (ALL, OWN, NONE), só como dica para a interface; 409 sem seleção.

Gestão de equipe da clínica selecionada em `/api/v1/clinic/team` (CRM-012, [permissoes.md](permissoes.md)): listar, mudar papel e desativar membro. A clínica vem sempre da sessão.

Cookies: BRANLY_SESSION, HttpOnly, SameSite=Lax, somente cookie (sem URL rewriting), expiração por inatividade de 30 minutos. SESSION_COOKIE_SECURE=true é obrigatório em implantação HTTPS; false no ambiente HTTP local. Sem token de acesso em localStorage. ID da sessão é renovado no login.

Login/logout são processados pelos filtros nativos do Spring Security, não por controllers fictícios. Os demais endpoints têm contratos OpenAPI no SessionController. As duas operações de filtro estão documentadas em config/OpenApiConfiguration. Seleção negada responde 403 com {"message"}; UUID malformado responde 400.

## Respostas de login

Senha errada, conta inexistente, conta inativa e conta não verificada respondem igual: 401 `INVALID_CREDENTIALS`, com o mesmo corpo. O estado da conta só é verificado depois da comparação da senha (`SecurityConfiguration.authenticationProvider`), então as quatro respostas têm custo de hash equivalente e o tempo de resposta não revela quais e-mails existem. Erros seguem [api-convencoes.md](api-convencoes.md).

## Revogação e limitação

A cada requisição autenticada, o servidor consulta estado e auth_version. Desativar usuário, alterar senha/e-mail/verificação incrementa versão no banco e invalida a sessão no próximo acesso. Participação/estado da clínica também são revalidados quando houver seleção; revogação elimina a seleção e retorna 403. A identidade global pode continuar ativa para outras clínicas.

Limitação inicial: 20 tentativas por endereço em janela de 60 segundos, contadas separadamente para o login e para as rotas anônimas de convite (`/v1/invitations/preview` e `/accept`, que também calculam hash de senha), com no máximo 10 mil endereços retidos; 429 ao exceder. É local ao processo e se reinicia com ele. Nginx substitui X-Forwarded-For pelo endereço real do cliente conectado. Antes de usar múltiplas réplicas, migrar limitação e sessões para armazenamento compartilhado e definir a política de proxies confiáveis.

## Convites (ADR-028)

- **Token:** 32 bytes aleatórios em base64url, guardados só como SHA-256. O link é `APP_PUBLIC_ORIGIN/convite#token=...`; o fragmento nunca chega a servidores, e o frontend envia o token no corpo. Validade de 7 dias; reenviar gira o token e invalida o anterior.
- **Aceite** (`POST /v1/invitations/accept`, anônimo ou com sessão, sempre com CSRF):
  - e-mail sem conta: cria a conta já verificada, com nome e senha (12 caracteres a 72 bytes);
  - conta verificada: exige estar logado com ela; a senha nunca é trocada;
  - conta não verificada: o token prova o e-mail e redefine nome e senha; `auth_version` sobe e derruba sessões antigas;
  - conta inativa: recusada, sem reativar;
  - sessão de outra conta: `INVITATION_ACCOUNT_MISMATCH`.
- **Sem login automático:** depois do aceite, a pessoa entra pelo login normal.
- **Autoridade de quem emitiu:** é conferida de novo no aceite. Um gestor rebaixado perde os convites que emitiu; o convite de gestor de uma agência morre se a clínica já tem dono, se a agência foi suspensa ou se quem emitiu saiu dela.
- **Onde cada convite vale:** o de equipe só em clínica `ACTIVE`; o de gestor da agência só em clínica `PROVISIONING` ou `AWAITING_OWNER` que nunca teve participação `OWNER`, e nunca para um admin ativo da própria agência.
- **Contas não verificadas:** a API não tem `UPDATE` em `user_accounts`. A tomada pelo token passa pela função `claim_unverified_account` (V10), que só altera conta ativa e não verificada.
- **Limites de envio:** 5 e-mails por convite, no mínimo 1 minuto entre eles e 100 por clínica a cada 24 horas (`INVITATION_SEND_LIMIT`).
- **E-mail** (ADR-021): `MAIL_PROVIDER=local` grava arquivos e é recusado com origem HTTPS; `resend` usa a API HTTP com `RESEND_API_KEY`. O envio acontece depois do commit; falha deixa o convite pendente e a resposta traz `delivery: FAILED`.

## O que ainda falta

- Cadastro, confirmação de e-mail, convite, recuperação de senha e segundo fator.
- Provisionar banco/papel por clínica e conectar JPA tenant.
- Separar credenciais administrativas e de execução do banco central.
- Aplicar matriz granular de permissões aos futuros endpoints de negócio.
- Auditoria de acesso, fluxo de recuperação do segundo fator e gestão de sessões pelo usuário.
- Tela de login e cliente HTTP no frontend.

Não há usuários padrão nem endpoint público para inserir credenciais. Esta entrega não é o módulo completo de acesso nem libera uso em produção. A exigência de e-mail verificado está implementada, mas o fluxo de verificação ainda não.

## Verificações

Desde a ADR-017, `mvnw clean verify` executa 19 testes unitários/ArchUnit e 10 de integração com PostgreSQL (Testcontainers), que automatizam o fluxo HTTP descrito abaixo; ver [qualidade.md](qualidade.md). Antes eram sete testes focados.

Teste HTTP via Nginx com dados temporários: anônimo 401; login sem CSRF 403; senha incorreta 401; login válido 204; identidade 200; clínica alheia 403; clínica autorizada 200; participação revogada 403; usuário desativado 401; novo login 204; logout 204; acesso posterior 401. Os dados de teste foram removidos. A suíte completa com Testcontainers não foi executada nesta etapa.