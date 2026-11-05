# Branly CRM — Frontend

Next.js com App Router, TypeScript, Tailwind CSS e shadcn/ui.

## Desenvolvimento

Dentro de frontend, execute:

```sh
npm install
npm run dev
```

Abra http://localhost:3000.

## Verificação

```sh
npm run lint
npx tsc --noEmit
npm run build
```

## Organização

- src/app: rotas e layouts.
- src/components/ui: componentes shadcn/ui.
- src/lib: utilitários compartilhados.

O frontend consumirá a API Java/Spring. Login e regras de negócio ainda não foram implementados. Não armazene segredos em variáveis NEXT_PUBLIC_.
