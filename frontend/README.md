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

## Teste local de contatos (CRM-022)

1. Entre com um atendente sintético, selecione a clínica e abra [Contatos](http://localhost:3000/app/contatos). Use **Novo contato**, cadastre nome e telefone fictícios e confira o perfil. Volte à lista e busque pelo nome ou pelo telefone.
2. Tente cadastrar o mesmo telefone. Confira o aviso e o link para o perfil existente. Em duas abas, abra a edição do mesmo contato e salve uma alteração na primeira. Ao salvar na segunda, consulte a versão atual e revise o rascunho antes de reenviar.
3. No perfil, crie uma etiqueta no catálogo, vincule-a ao contato e teste o filtro na lista. Registre uma preferência de comunicação e confira o estado atual e o histórico. Os horários aparecem em UTC enquanto o contrato não expõe o fuso da clínica.
4. Com um gestor sintético, arquive o contato e confirme que só aparece ao marcar **Incluir arquivados**. Restaure-o pelo perfil. Com um perfil de consulta, confira que a leitura está disponível e que as ações de alteração estão ausentes.
5. Em uma conta de teste com vínculos A e B, abra o perfil de A em duas abas. Troque para B em uma delas. A outra deve sair do perfil de A. Abrir manualmente em B a URL do contato de A deve resultar em contato não encontrado.
6. Repita o cadastro e a busca com a janela estreita e usando Tab, Shift+Tab e Enter. Confira o foco visível, as mensagens de erro e a abertura do perfil.

O termo da busca fica em memória na página e segue no parâmetro `q` da API. Use apenas dados sintéticos até que a configuração de logs do proxy oculte esse parâmetro (CRM-072).

## Teste local da carteira da agência (FE-005)

1. Abra [Carteira da agência](http://localhost:3001/agencia) com uma conta sintética de agência e conclua o segundo fator. Com acesso somente à agência, confira se o login leva à carteira. Com acesso também a uma clínica, alterne pelo menu.
2. Em uma conta com mais de uma agência, selecione cada uma e confira as respectivas clínicas e situações. Teste também uma conta sem acesso à agência.
3. Em **Nova clínica**, informe um nome fictício e um e-mail `@example.test`. Confira a situação da clínica e o resultado do convite. Se a resposta se perder, use **Retomar cadastro** na mesma aba. Após recarregar, preencha o mesmo nome e e-mail. A aba guarda apenas o identificador da solicitação até a confirmação.
4. Reemita o convite de uma clínica em preparação ou aguardando gestor. Informe o e-mail de destino. A carteira não informa o endereço anterior e não apresenta dados de pacientes.
5. Repita o fluxo em uma janela estreita usando Tab, Shift+Tab, Enter e Esc.

## Teste local de equipe e convites (FE-003)

1. Abra [Equipe](http://localhost:3000/app/equipe) com um gestor sintético. Alterne os filtros de papel e situação e confira a paginação.
2. Convide uma pessoa com e-mail `@example.test`. Confira o resultado do envio e o convite na lista. Reenvie e confirme que o link anterior deixa de funcionar. Revogue e confira que a lista é atualizada.
3. Altere o papel e desative um membro de teste. Confira o filtro **Inativos**. Repita com um gestor operacional e uma conta somente leitura para verificar as diferenças de acesso.
4. Em duas abas, troque para uma segunda clínica de teste. Confirme que a lista e qualquer diálogo da clínica anterior desaparecem. Repita usando Tab e Enter em uma janela estreita.

A listagem de convites informa o vencimento. O resultado da entrega do e-mail aparece depois de enviar ou reenviar, conforme a resposta da API.

## Teste local do funil (CRM-033)

1. Abra [Funil de vendas](http://localhost:3000/app/funil) com uma clínica de teste provisionada com as migrações de funil e leads. Use contatos e leads sintéticos.
2. No Kanban, arraste um lead para outra etapa aberta e recarregue a página para conferir a persistência. Repita com **Mover**, usando Tab e Enter, em uma janela estreita.
3. Alterne para **Lista** e filtre por situação, etapa e temperatura. Abra um lead, confira os dados e registre perda com um motivo.
4. Abra o mesmo quadro em duas abas, mova o lead na primeira e tente mover a cópia antiga na segunda. A segunda deve desfazer a prévia e pedir que você atualize o funil.

O contrato atual não expõe a origem do lead. O fechamento como ganho passa pelo registro de vendas, previsto na CRM-035.

## Teste local do catálogo (CRM-034)

1. Em [Catálogo](http://localhost:3000/app/catalogo), use uma conta sintética de gestor para cadastrar um procedimento. Informe o valor de referência em reais, como `250,75`, e confira o valor mostrado na lista.
2. Edite o procedimento e desative-o. Confira os filtros **Ativos**, **Inativos** e **Todos**. Reative o item e repita o fluxo em **Profissionais**.
3. Tente cadastrar um nome já utilizado, inclusive por um item inativo. Em duas abas, abra a edição do mesmo item e confira que a versão antiga pede recarga após a primeira edição salvar.
4. Com uma conta de atendente, consulte o catálogo. As ações de cadastro, edição e mudança de situação ficam disponíveis para quem tem permissão de gestão. Repita usando Tab e Enter em uma janela estreita.

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

Após validar o segundo fator, o painel consulta as áreas disponíveis e confirma a clínica ativa para a área da clínica. A carteira da agência usa o vínculo da agência e os dados cadastrais disponíveis na API.
