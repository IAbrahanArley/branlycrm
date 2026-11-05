# Plano da próxima entrega — cadastro e ativação de clínica

Data: 05/11/2026. Status: planejamento; itens abaixo ainda não implementados. Não substitui ADRs aceitas. Parâmetros propostos precisam ser confirmados na implementação.

## Base concluída

Docker com Nginx, Next.js, Spring e PostgreSQL/pgvector; registro central de tenants e identidades; login por sessão, CSRF, logout, seleção autorizada, revogação e limitação local de tentativas. Sete testes Java focados e teste HTTP integrado passaram. Build frontend em Docker validado. Ainda não há bancos de clínicas criados pelo cadastro nem telas de acesso.

## Resultado esperado

Uma pessoa confirma seu e-mail, cria a clínica, acompanha a preparação do banco e entra como administradora somente quando o ambiente está íntegro e isolado. Repetições e falhas não criam contas, bancos ou participações duplicados.

## Sequência de implementação

| Ordem | Entrega | Critério de conclusão |
|---|---|---|
| 1 | Credenciais e persistência | Papel central sem privilégios de provisionamento; conta provisionadora isolada; acesso de tenant sem fallback; credencial A não conecta a B ou ao controle |
| 2 | Contrato e modelo do cadastro | Identidade, pedido de cadastro, chave idempotente, token de verificação e estado da operação modelados; controllers com interface Swagger e Override |
| 3 | Verificação de e-mail | Token aleatório de uso único, apenas hash persistido, prazo e reenvio limitados; respostas públicas sem enumeração de contas |
| 4 | Provisionador assíncrono | Criação de banco/papéis, migração, identidade e permissões; execução retomável com estados e exclusão mútua |
| 5 | Ativação | Conferência de schema, identidade e isolamento; participação ADMIN criada e tenant ativado somente após sucesso |
| 6 | Interface de acesso | Login, cadastro, verificação e acompanhamento, usando sessão e CSRF já implementados |
| 7 | Integração e documentação | Testes completos com PostgreSQL, falhas parciais, repetição, concorrência e credenciais cruzadas; SCs/ACs atualizados |

## Etapa 1 — limites de credenciais

- Separar conexão central da conexão de provisionamento; não usar usuário administrativo no tráfego normal da API.
- Runtime central acessa apenas tabelas necessárias. Provisionador recebe privilégios mínimos e não fornece credencial administrativa à aplicação de negócio.
- Por clínica: proprietário/migrador separado de runtime; runtime sem SUPERUSER, CREATEDB ou CREATEROLE e sem alteração da identidade ou estrutura.
- Revogar CONNECT de PUBLIC em bancos de clínicas e no controle; validar grants explicitamente.
- Definir um contrato de armazenamento e rotação de segredos. Referências de segredo já existem; escolher implementação local e de hospedagem antes de automatizar.
- Restringir as consultas de autorização também a identidade ativa/verificada, além de participação e clínica ativas, inclusive fora de HTTP.
- Separar explicitamente persistência central e tenant antes de adicionar entidades de agenda/contatos.

## Etapas 2 e 3 — identidade e cadastro

Modelar pedido de cadastro com chave idempotente única, hash do conteúdo relevante, identificador público opaco e estado. Não reutilizar chave para payload diferente. Evitar retornar dados de uma operação para solicitante sem autorização.

Para e-mail já registrado, não trocar senha nem vincular nova clínica só pelo endereço informado. A criação de outra clínica por identidade existente exige autenticação dessa identidade. As respostas públicas não revelam a existência da conta.

Tokens de e-mail: aleatórios criptograficamente, armazenados por hash, uso único e invalidação após uso. Expiração, reenvio e volume máximo de tentativas serão definidos. Senhas nunca aparecem em logs, filas ou mensagens; somente hash de senha na identidade.

Proposta: confirmar e-mail antes de iniciar criação de banco, reduzindo ambientes abandonados. Escolher transporte de e-mail; em desenvolvimento pode ser usado capturador local, sem envio a pessoas reais durante testes.

## Etapas 4 e 5 — provisionamento

1. Confirmar autenticação/verificação e autorização do pedido.
2. Reservar UUID permanente e nomes derivados em PROVISIONING, com tarefa persistente na mesma transação central.
3. Worker assume tarefa com exclusão mútua, tentativas limitadas e registro de etapa.
4. Criar papéis e banco fora da transação central; conferir proprietário antes de reutilizar objetos após falha.
5. Aplicar migrações próprias do tenant e registrar identidade.
6. Guardar segredo, configurar grants e testar conexão runtime e rejeição cruzada.
7. Concluir participação ADMIN e status ACTIVE em transação central; registrar resultado da operação.
8. Em falha: FAILED e diagnóstico interno sem segredos; permitir retomada administrativa. Não apagar banco com dados como compensação automática.

O worker nunca aceita nomes SQL, URL JDBC ou credenciais enviados pelo navegador. São derivados do UUID e da configuração confiável.

## Contratos a detalhar

Rotas propostas, ainda não existentes: pedido de cadastro, confirmação de e-mail, reenvio limitado e consulta autorizada do estado do onboarding. A operação de provisionamento é interna. Respostas de erros devem ter códigos estáveis sem SQL, credenciais ou stack traces.

## Testes de aceitação obrigatórios

- Cadastro repetido e concorrente não duplica identidade, banco ou participação.
- Chave idempotente com outro payload é rejeitada sem alterar cadastro existente.
- Token vencido, reutilizado ou incorreto é recusado; reenvio não permite ativação com token invalidado.
- Não é possível tomar conta existente informando seu e-mail.
- Falha após criar papel, banco ou migrar pode ser retomada sem novo UUID.
- Clínica permanece inacessível antes de ACTIVE e após suspensão.
- Credencial A não conecta a B nem ao controle e não altera schema/identidade.
- A identidade gravada no banco corresponde ao registro central.
- Senhas e tokens brutos não aparecem em logs nem respostas.
- Usuário sem participação não acompanha operação alheia.
- Rodar a suíte completa com Testcontainers; a validação anterior cobriu apenas os sete testes focados e o teste HTTP.

## Depois desta entrega

Convites e permissões granulares; recuperação de senha; segundo fator de administradores; cadastro de profissionais e serviços; agenda; WhatsApp; IA. Recuperação e segundo fator continuam necessários para completar o módulo de acesso antes de produção.

## Progresso — 05/11/2026

Etapa 1 parcialmente concluída: credenciais centrais separadas, migração fora da API, permissões verificadas no volume atual e instalação vazia testada. Autorização passou a verificar identidade ativa/verificada também no repository. Ainda faltam papéis por clínica, teste de credenciais cruzadas, armazenamento de segredos e integração JPA tenant. Etapas 2 a 7 continuam planejadas.

## Detalhamento da próxima etapa

A sequência imediata está em plano-isolamento-clinicas.md: contratos de segredo, papéis por tenant, provisionamento interno, prova A/B e persistência tenant. Essas tarefas são planejadas; ainda não há cadastro público ou banco de clínica provisionado pela aplicação.
