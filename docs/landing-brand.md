# Landing da marca Branly

> Registro da primeira versão. A implementação atual evoluiu conforme o briefing premium; ver landing-premium.md. A autorização posterior inclui dados fictícios identificados, simulador, sombras e gradientes sutis.

## Status e localização

Implementação local na branch feat/landing-brand, em worktree separado de api/ para não interferir no agente do backend. Prévia: http://localhost:3001. Não publicada, não integrada à branch principal e versionada nesta entrega. A tarefa de marketing está em landing-task.md, separada do TASKS.md do agente de backend para evitar conflito na integração.

## Direção visual

Skill design-taste-frontend: Type and nothing else, seed 1108223444. A tipografia conduz a primeira tela; a jornada comercial é a interação central. A marca dá unidade às seções por campos planos de ciano e azul escuro.

A paleta do usuário é a fonte: #011127, #00DFE5, #01247D e #000190. Ciano no menu; azul escuro no hero e rodapé; branco e cinza neutro no conteúdo. Red Hat Display variável, auto-hospedada, licença OFL incluída em frontend/src/app/fonts/OFL.txt. Fonte obtida de https://github.com/google/fonts/tree/main/ofl/redhatdisplay.

Logo original copiada sem alterar o arquivo. No menu, filtro CSS produz versão monocromática escura para contraste; no rodapé é exibida a versão colorida original. A moldura CSS remove visualmente o excesso de área transparente. Ícone da página também utiliza a imagem fornecida.

A direção foi adaptada para manter o menu ciano explicitamente solicitado. Não foram incluídas fotos, vídeo, números, avaliações ou clientes inventados. Nenhuma foto de banco é apresentada como equipe ou clínica cliente. A ausência de fotos/vídeo é intencional: ainda não há material próprio nem screenshots reais das funcionalidades comerciais.

## Conteúdo e prova

- Hero: apresenta CRM para clínicas e indica desenvolvimento.
- Jornada: ilustração interativa do fluxo planejado, sem contatos ou dados de clientes; não se apresenta como sistema em operação.
- Produto: contatos/oportunidades e agenda/tarefas descritos como escopo em desenvolvimento; equipe/permissões descritas conforme backend atual do repositório de origem.
- FAQ: público, foco comercial, papéis e indisponibilidade de contratação.
- CTA: navegação local entre seções. Não há formulário, envio de e-mail, coleta, preço ou promessa de demonstração comercial disponível.
- Sem promoção de WhatsApp/IA, conforme escopo vigente.

## Verificações

- npm run lint: passou sem avisos após ajustar export de configuração PostCSS.
- next typegen e tsc --noEmit: passaram.
- Build Next.js no Docker: passou, incluindo TypeScript.
- Navegador real: 15 verificações de abas, teclado, troca de conteúdo, FAQ, âncoras, ausência de formulário, overflow em 320/390/768/1440 e menu móvel.
- Inspeção visual em 1440/768/390: títulos em até duas linhas, cinco tamanhos tipográficos, foco e redução de movimento previstos. Capturas revisadas.
- Inspector: primeira rodada apontou ausência de fotos e variações tipográficas/texto pequeno; tipografia e texto pequeno corrigidos. Rodada final: zero falhas e avisos com allowances explícitas few-pictures/no-footage, justificadas pelo pedido de não usar conteúdo falso e pela ausência de acervo real.

## Revisão visual

Hierarquia e marca bem definidas, composição móvel própria e navegação funcional. A página explica o projeto, mas ainda não prova uma experiência comercial funcional. Não deve ser promovida como produto disponível. Avaliação interna subjetiva: design 6,5; usabilidade 8; criatividade 7; conteúdo 8; ponderada 7,15/10. O limite visual principal é a ausência de screenshots reais, que devem substituir ilustrações conceituais quando os módulos existirem.

## Pendências

Definir contato comercial/destino de captação antes de adicionar formulário. Não inventar endereço, e-mail, domínio ou termos legais. Revisar texto quando cadastro e contratação estiverem realmente disponíveis. Integrar a branch e publicar somente por solicitação do responsável. Nenhuma mudança de backend, autenticação ou tenancy nesta entrega.
