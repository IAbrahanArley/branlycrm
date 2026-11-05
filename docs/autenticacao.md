# Identidade, sessões e seleção de clínica

Implementação inicial em 05/11/2026. ADR-015.

## Camadas

UserAccountRepository, TenantRegistryRepository e TenantIdentityRepository concentram SQL. IdentityService valida estado da identidade; TenantAccessService valida participação; SessionService coordena seleção. Controllers têm interface documentada no mesmo arquivo e implementação com Override. DTOs de resposta ficam em arquivos próprios.

Banco central: user_accounts com UUID permanente, e-mail normalizado único, hash bcrypt, estado ativo, verificação de e-mail e versão de autenticação. tenant_memberships referencia a identidade global com chave estrangeira. Papéis continuam por clínica: ADMIN, RECEPTION, PROFESSIONAL; não são autoridades globais na sessão.

## Protocolo HTTP

Prefixo externo no Docker: /api/v1/auth.

1. GET /csrf: retorna token, headerName e parameterName. Preservar cookie.
2. POST /login: application/x-www-form-urlencoded, campos email e password, header CSRF e cookie. Retorna 204 ou 401; não há redirecionamento para página de login.
3. GET /csrf novamente após login, pois o token anterior é invalidado.
4. GET /me: subjectId e email, sem credenciais. GET /clinics: participações ativas em clínicas ativas.
5. PUT /clinic/{tenantId}: exige CSRF e participação ativa; armazena UUID na sessão e retorna tenantId. O UUID do sujeito vem do principal autenticado, não do navegador.
6. POST /logout: exige CSRF, invalida sessão e remove cookie; retorna 204. Obter novo token antes de novo login.

Cookies: BRANLY_SESSION, HttpOnly, SameSite=Lax, somente cookie (sem URL rewriting), expiração por inatividade de 30 minutos. SESSION_COOKIE_SECURE=true é obrigatório em implantação HTTPS; false no ambiente HTTP local. Sem token de acesso em localStorage. ID da sessão é renovado no login.

Login/logout são processados pelos filtros nativos do Spring Security, não por controllers fictícios. Os demais endpoints têm contratos OpenAPI no SessionControllerDoc. As rotas de filtros estão descritas aqui; documentação OpenAPI dessas duas operações ainda precisa ser adicionada.

## Revogação e limitação

A cada requisição autenticada, o servidor consulta estado e auth_version. Desativar usuário, alterar senha/e-mail/verificação incrementa versão no banco e invalida a sessão no próximo acesso. Participação/estado da clínica também são revalidados quando houver seleção; revogação elimina a seleção e retorna 403. A identidade global pode continuar ativa para outras clínicas.

Limitação inicial: 20 tentativas de login por endereço em janela de 60 segundos, com no máximo 10 mil endereços retidos; 429 ao exceder. É local ao processo e se reinicia com ele. Nginx substitui X-Forwarded-For pelo endereço real do cliente conectado. Antes de usar múltiplas réplicas, migrar limitação e sessões para armazenamento compartilhado e definir a política de proxies confiáveis.

## O que ainda falta

- Cadastro, confirmação de e-mail, convite, recuperação de senha e segundo fator.
- Provisionar banco/papel por clínica e conectar JPA tenant.
- Separar credenciais administrativas e de execução do banco central.
- Aplicar matriz granular de permissões aos futuros endpoints de negócio.
- Auditoria de acesso, fluxo de recuperação do segundo fator e gestão de sessões pelo usuário.
- Tela de login e cliente HTTP no frontend.

Não há usuários padrão nem endpoint público para inserir credenciais. Esta entrega não é o módulo completo de acesso nem libera uso em produção. A exigência de e-mail verificado está implementada, mas o fluxo de verificação ainda não.

## Verificações

Sete testes Java focados passaram: tenant ausente/desconhecido, vínculo de banco, normalização de e-mail, revogação, usuário não verificado e limitação de tentativas.

Teste HTTP via Nginx com dados temporários: anônimo 401; login sem CSRF 403; senha incorreta 401; login válido 204; identidade 200; clínica alheia 403; clínica autorizada 200; participação revogada 403; usuário desativado 401; novo login 204; logout 204; acesso posterior 401. Os dados de teste foram removidos. A suíte completa com Testcontainers não foi executada nesta etapa.