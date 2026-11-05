# Estrutura e situação do projeto

Atualizado em 05/11/2026.

## Local correto

Repositório: C:\Users\abrah\OneDrive\Área de Trabalho\crm\api

Frontend: C:\Users\abrah\OneDrive\Área de Trabalho\crm\api\frontend

A criação inicial na pasta branlycrm foi corrigida por solicitação do usuário. O frontend foi movido, sem manter cópia ativa no caminho anterior.

## Organização

Monorepo: backend/, frontend/, docs/ e futuramente infra/. GitHub Projects é complementar, para planejamento; sua criação não foi solicitada nem executada. Não há repositórios separados definidos para frontend e backend.

A raiz atual também possui api/. O conteúdo e a localização definitiva do projeto Spring ainda precisam ser conferidos antes de reorganizar o backend; esta documentação não move essas pastas.

## Frontend existente

Next.js 16.4.5, React 19.3.0, TypeScript, Tailwind CSS e shadcn/ui. App Router com src/, alias @/*, npm e ESLint. Fontes do sistema, interface inicial em português e identificação Branly CRM. Componentes base e utilitários instalados; Tailwind configurado via PostCSS.

ESLint e TypeScript passaram antes da mudança de pasta. Servidor de desenvolvimento iniciou, mas a compilação de produção não foi validada: houve erro com Turbopack e tentativas posteriores sem conclusão. Essa pendência não deve ser considerada resolvida. O servidor anterior foi encerrado ao mover a pasta.

Commit do frontend: 557a1ca — chore: initialize Next.js frontend with shadcn ui.

Nenhuma tela funcional de login, agenda ou atendimento foi implementada. Não houve conexão ao backend nem uso do Lovable.

## Inicialização local

Dentro de frontend: npm install e npm run dev. Verificações: npm run lint, npx tsc --noEmit e npm run build. Dependências e artefatos gerados não devem ser versionados.


## Validação Docker — 05/11/2026

A compilação de produção do frontend passou dentro do Docker. Backend compilado, PostgreSQL conectado, Flyway V1 aplicado e vector 0.8.7 habilitado. Página e saúde da API retornaram HTTP 200 via Nginx em localhost:8088. A chamada interna frontend → backend também retornou 200/UP. Isso resolve a validação da imagem de produção; a falha anterior da compilação nativa no Windows não foi reavaliada.
