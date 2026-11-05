# Papel: Frontend

Implementa as interfaces em Next.js 16 / React 19 / TypeScript strict / Tailwind / shadcn/ui (`frontend/`). Só toca os arquivos que o coordenador atribuiu.

## Regras

- **Contrato:** consumir apenas contratos de API aprovados (OpenAPI do backend). Nenhuma regra de negócio ou autorização duplicada no navegador; o servidor decide.
- **Sessão:**
  - cookie `BRANLY_SESSION` HttpOnly; o navegador nunca guarda token em `localStorage`;
  - mutações enviam o CSRF obtido em `GET /api/v1/auth/csrf`, renovado após login e logout ([autenticacao.md](../autenticacao.md)).
- **Clínica:** a clínica ativa é escolhida por `PUT /api/v1/auth/clinic/{id}`; o identificador na URL é só seletor.
- **Estados:** carregamento, vazio, erro, sucesso, permissão negada (403) e sessão expirada (401) em todas as telas. Falha do servidor não deixa a interface em estado enganoso.
- **Acessibilidade:**
  - HTML semântico, labels persistentes, navegação por teclado, foco visível e erros associados aos campos;
  - nada que dependa só de cor;
  - mensagens de login e recuperação não revelam se a conta existe.
- **Componentes e dependências:** responsivo em desktop e celular; reutilizar os componentes shadcn/ui existentes antes de criar variantes. Biblioteca nova exige aprovação.
- **Dados:** nenhum segredo no bundle; dados de exemplo sempre sintéticos.

## Skills de UI

Use `web-design-guidelines` para revisar as telas alteradas. `design-taste-frontend` vale só para páginas públicas de marketing, nunca para o painel, a autenticação ou telas operacionais. Skill indisponível é uma limitação a registrar, não uma revisão executada.

## Checks

`npm run lint`, `npx tsc --noEmit` e `npm run build` em `frontend/`. Quando a tela depender da API, validar também no Compose (`localhost:8088`).

## Entrega

Arquivos alterados, estados cobertos, checks executados com resultado, verificações manuais de acessibilidade e limitações.
