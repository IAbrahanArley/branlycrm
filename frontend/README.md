# Branly CRM — Frontend

Next.js com App Router, TypeScript, Tailwind CSS e shadcn/ui.

## Desenvolvimento

Com o Compose de `crm/api` rodando em `localhost:8088`, execute em `crm/api-front/frontend`:

```sh
npm ci
npm run dev
```

Abra [o painel](http://localhost:3000/app). O Next repassa as chamadas a `/api` para o Compose.

## Teste local da FE-001

1. Em uma janela privada, abra `/app`. Na aba Rede do navegador, confirme o `401` de `/api/v1/auth/me` e a navegação para `/login`.
2. Para simular uma falha de rede, ative o bloqueio de requisições nas ferramentas do navegador para `*/api/v1/auth/me` e abra `/app` novamente. Confira a mensagem de erro. Desative o bloqueio e acione “Tentar novamente” para conferir a recuperação e o redirecionamento.
3. Use Tab para conferir o foco visível. No painel, o link “Ir para o conteúdo” deve levar ao conteúdo principal. Repita em uma janela estreita.
4. Na aba Aplicativo, confira que `localStorage` e `sessionStorage` da origem não contêm tokens.

## Teste local de login e logout

1. Abra [o login](http://localhost:3000/login). Use Tab para percorrer os campos e o botão de mostrar ou ocultar senha.
2. Envie um e-mail sintético inexistente, como `inexistente@example.test`, e uma senha de teste. O login deve exibir a mesma mensagem usada para senha incorreta.
3. Entre com uma conta sintética ativa e verificada no Compose. Se a conta exigir segundo fator, conclua a etapa na página de segurança. No painel, escolha uma clínica e confirme em **Acessar clínica**. Confira o nome e o papel no cabeçalho. Se houver mais de um vínculo, use **Trocar clínica**.
4. Clique em **Sair** e confirme o retorno à tela de login. Ao tentar reabrir `/app`, a página deve pedir autenticação novamente.
5. Se a API retornar `429`, confira o prazo no cabeçalho `Retry-After` da aba Rede. O botão **Entrar** é liberado após esse prazo.

No desktop, use **Recolher menu** na parte inferior da barra lateral. No celular, abra a navegação pelo botão no topo e feche com **Esc** ou pelo botão de fechar. O e-mail da sessão aparece no menu da conta, onde também ficam **Segurança da conta** e **Sair**.

## Teste local do segundo fator

1. Para um proprietário ou administrador de agência sintético sem fator, entre e gere uma chave em [Segurança da conta](http://localhost:3000/seguranca). Adicione a chave manualmente em um autenticador TOTP e confirme com os seis dígitos.
2. Confira que os dez códigos de recuperação aparecem uma única vez. Guarde-os e confirme antes de continuar. Use um novo código do aplicativo a cada operação.
3. Saia e entre novamente. Verifique o acesso com um código TOTP ou um código de recuperação. Em **Segurança da conta**, confira a contagem restante e teste as ações de gestão com a conta sintética.

Os testes abaixo exercitam a renovação do CSRF, a recusa de mutações e a revogação com respostas simuladas.

## Teste local de convites

1. Com um convite sintético emitido pelo ambiente de teste, abra o link na origem do frontend e mantenha o fragmento original. A tela remove o fragmento da barra e apresenta a clínica e o papel, ou a agência.
2. Use uma janela privada para testar o cadastro de uma conta nova. Para uma conta verificada, entre no próprio fluxo do convite e confirme o aceite. Um convite aceito leva ao login. Para reabrir o convite após recarregar a página, use o link original.
3. Na aba Rede, confira o cabeçalho `Referrer-Policy: no-referrer` na página. O token segue apenas no corpo JSON da prévia e do aceite.

## Verificação

```sh
npm run lint
npm run typecheck
npm run build
node --disable-warning=MODULE_TYPELESS_PACKAGE_JSON --test tests/*.test.mjs
```

## Organização

- src/app: rotas e layouts.
- src/components/ui: componentes shadcn/ui.
- src/lib: utilitários compartilhados.

- `src/lib/api`: cliente de mesma origem, erros da API e operações de sessão. O CSRF vive em memória e a sessão usa o cookie HttpOnly emitido pelo backend.
- `src/components/panel`: layout protegido e estados reutilizáveis. Qualquer leitura protegida futura deve consultar a API, que verifica a sessão e as permissões.

Após validar o segundo fator, o painel consulta as áreas disponíveis e confirma a clínica ativa com a API. A rota e a navegação da carteira da agência serão entregues na FE-005.
