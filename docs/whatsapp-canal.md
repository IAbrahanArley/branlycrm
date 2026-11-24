# Canal WhatsApp: base (CRM-081a), conexão (CRM-081b), webhook (CRM-082a) e worker de entrada (CRM-082b)

Implementado em 17/11/2026. Desenho completo na ADR-036; este documento cobre o que já existe em código.

## Peças

| Peça | Onde | Quem recebe |
|---|---|---|
| Registro central de canais | migração central V16: `whatsapp_channels` e `whatsapp_onboarding_tasks` | API e worker, com permissões diferentes (abaixo) |
| Papel do worker | `crm_whatsapp_worker`, criado pelo `infra/postgres/bootstrap-roles.sh` | Só o serviço `whatsapp-worker` |
| Cifra dos tokens | `whatsapp/secrets`: `ChannelSealer` (chave pública) e `ChannelUnsealer` (chave privada) | O `ChannelSealer` existe na API e no worker. O `ChannelUnsealer` só existe com `app.whatsapp.worker.enabled`, e o ArchUnit só deixa o pacote `whatsapp.worker` usá-lo |
| Acesso de serviço | `TenantConnectionRepository.openForChannel` e `ClinicTenant.ofChannel` | O ArchUnit só deixa o módulo `whatsapp` (webhook e worker) usá-los |
| Processo | `APP_MODE=whatsapp-worker`, serviço `whatsapp-worker` no Compose, sem HTTP | — |

## Permissões no banco central

- **API** (`crm_control_runtime`):
  - lê só as colunas de roteamento de `whatsapp_channels`; **não lê** `sealed_token` nem `sealed_pin`;
  - cria canais `PENDING` com o token já cifrado (sem acesso às colunas da conta e do número, não consegue criar um canal ativo);
  - cria tarefas sempre `PENDING`, por padrão;
  - atualiza só `inbound_pending_since`;
  - lê e cria tarefas de conexão;
  - nunca exclui.
- **Worker** (`crm_whatsapp_worker`):
  - lê canais e tarefas e atualiza **só as colunas de estado**: nunca a clínica, o id nem a autoria, porque o `openForChannel` confia no `tenant_id`;
  - lê as colunas de roteamento de `tenants`;
  - não tem acesso a contas, participações nem senhas;
  - não exclui.
- O `infra/postgres/verify-roles.sh` confere as duas regras acima no ambiente Docker.

## Regras garantidas pelo banco

- **Um canal vivo por número e um por clínica** (índices parciais que ignoram os desconectados).
- **Situações:**
  - canal `ACTIVE` exige conta, número e token cifrado;
  - canal `DISCONNECTED` não guarda token nem PIN;
  - `FAILED` exige um código de falha.

## Acesso de serviço (`openForChannel`)

- **Autorização:** abre o banco da clínica pelo canal, sem usuário. Em toda conexão, o canal precisa estar `ACTIVE`, a clínica `ACTIVE` e o canal pertencer à clínica pedida.
- **Recusas:** canal pendente, com falha ou desconectado, clínica suspensa ou canal de outra clínica recebem `TenantAccessException`.
- **Conexão:** o resto é igual ao acesso de usuário: o segredo da clínica, o pool por clínica e a conferência de `tenant_identity`.

## Chaves

Gerar a próxima versão do par:

```bash
bash infra/whatsapp/generate-keys.sh ./.whatsapp-keys
```

- **Onde ficam:**
  - `public/channel-key-v<N>.pub.pem` vai montado na API e no worker;
  - `private/channel-key-v<N>.pem` vai montado **só no worker**;
  - a pasta `.whatsapp-keys/` é ignorada pelo Git.
- **Formato:** RSA de pelo menos 3072 bits. Cada valor cifrado leva no cabeçalho a versão da chave e o contexto (canal e finalidade), e um valor copiado para outro canal ou campo não abre.
- **O que a cifra garante:** sigilo, não origem. Quem tem a chave pública, como a API, consegue produzir valores válidos. O contexto só impede trocar valores entre canais ou campos.
- **Conferência na subida:** o worker testa se a chave pública mais nova tem a privada correspondente. Se não tiver, registra um erro, porque nenhuma conexão nova conseguiria abrir o token.
- **Rotação:**
  1. gere uma nova versão;
  2. reinicie a API e o worker; a API passa a cifrar com a versão nova;
  3. o worker continua abrindo as versões antigas;
  4. a recifragem das linhas antigas entra junto com o worker de conexão (CRM-081b).
- **Backup:** guarde a pasta `private/` **fora da VM e separada do backup do banco**. Sem ela, todas as clínicas precisam reconectar o WhatsApp.
- **Permissões na VM Linux:** o script cria `private/` com 700 e os arquivos com 600, pertencentes ao usuário do host. O container roda como o usuário `app`. Antes de subir o worker, dê a pasta a esse usuário:
  ```bash
  sudo chown -R "$(docker compose run --rm --no-deps --entrypoint id whatsapp-worker -u)" .whatsapp-keys/private
  ```
  Sem isso, o worker não consegue ler a chave e não sobe. No Docker Desktop do Windows isso não acontece.

## Ambiente local

- **Senha do worker:** acrescente `WHATSAPP_WORKER_PASSWORD` ao `.env` (veja o `.env.example`). Sem ela, o Compose não sobe.
- **Sem chaves:** a API sobe e só a cifragem falha (`NOT_CONFIGURED`). O worker sobe e avisa no log que não consegue abrir tokens.

## Webhook (CRM-082a)

Rota pública `POST` e `GET /api/v1/webhooks/whatsapp`. É a URL de retorno configurada no app da Meta.

- **Verificação (`GET`):** com `hub.mode=subscribe` e o token de verificação certo (comparado em tempo constante), devolve o `hub.challenge` em `text/plain`. O desafio só aceita letras, números, `_` e `-`, até 128 caracteres. Qualquer outra coisa responde 403.
- **Entrega (`POST`), nesta ordem:**
  1. lê o corpo bruto, até 3 MiB (413 acima disso);
  2. confere `X-Hub-Signature-256` (HMAC-SHA256 com o segredo do app, em tempo constante) **antes de interpretar o JSON**; sem assinatura válida, 401 e nada é gravado;
  3. separa um evento por mensagem, status ou mudança de conta;
  4. roteia pelo registro central, nunca pelo payload:
     - mensagens e status pelo `phone_number_id`, e o `entry.id` precisa ser o `waba_id` do canal;
     - eventos da conta pelo `entry.id`. Se a conta tiver canais de mais de uma clínica (uma agência), o evento só vai para a clínica do número que ele cita em `display_phone_number`; sem número, é descartado, para nunca copiar o evento de uma clínica em outra;
  5. grava em `whatsapp_inbound_events` do banco da clínica, numa transação por clínica, com `ON CONFLICT DO NOTHING`;
  6. depois do commit, marca `inbound_pending_since` e dispara `NOTIFY whatsapp_inbound` só com o id do canal;
  7. responde 200.
- **Descartes (respondem 200):** número ou conta desconhecidos, canal pendente, com falha ou desconectado, clínica suspensa, número de uma conta diferente da entrada. O log registra só o motivo, o id do canal e a contagem.
- **Falhas (respondem 503 com `Retry-After`):** qualquer falha de gravação numa clínica, segredos ausentes ou excesso de entregas simultâneas. Não existe 200 parcial: a Meta reenvia a entrega inteira, e a deduplicação torna seguro regravar nas clínicas que já tinham recebido.
- **Deduplicação:**
  - mensagem e eco do app (`smb_message_echoes`): `wamid`;
  - status: `wamid` e situação, então `delivered` e `read` do mesmo envio são dois eventos;
  - demais eventos: SHA-256 da conta, do `entry.time` e da mudança inteira, então o mesmo aviso em outra entrega é um evento novo, e o reenvio da Meta continua duplicado;
  - a chave guardada é sempre um prefixo e um SHA-256, nunca o identificador cru.
- **Campos aceitos:** `messages`, `smb_message_echoes` e as atualizações de conta, número, modelos e qualidade. Os demais são ignorados, inclusive o `history`, porque a importação do histórico da coexistência foi recusada (ADR-036).
- **O que fica guardado:** o JSON de cada mensagem ou status, com os metadados e os contatos da entrega. Pode trazer dados de saúde, e o worker apaga o JSON 30 dias depois de processado (CRM-082b).
  - O caractere NUL, que o `jsonb` recusa, é removido antes de gravar. Sem isso, a entrega falharia em toda nova tentativa.
- **Concorrência:** no máximo `app.whatsapp.webhook.max-concurrent` entregas gravando ao mesmo tempo (padrão 8). Acima disso, depois de 2 segundos de espera, responde 503 e a Meta reenvia. Isso substitui o pool próprio previsto na ADR-036: o pool por clínica é reaproveitado, e o limite impede que o webhook tome as conexões dos usuários.
- **Segurança da rota:** `SecurityFilterChain` própria e anterior à de sessão, só para esse caminho, sem sessão, cookie, CSRF nem cache de requisição. Nenhum filtro registra o corpo.
- **Nginx:**
  - `location` exata com `client_max_body_size 3m`;
  - requisições com `X-Hub-Signature-256`: limite de 100 por segundo, com rajada de 500, compartilhado e não por IP, porque a Meta envia de vários endereços;
  - requisições sem o cabeçalho, como o `GET` de verificação: limite estrito por IP, para não gastarem o limite compartilhado;
  - access log sem a query, que contém o token de verificação, e error log só com erros críticos, porque ele registraria a linha da requisição. Se o token aparecer em algum log, troque-o no arquivo e na Meta.

### Segredos do webhook

O segredo do app e o token de verificação vêm de **arquivos montados**, nunca de variável de ambiente:

| Arquivo | Conteúdo | Quem recebe |
|---|---|---|
| `.whatsapp-meta/app-secret` | o segredo do app (Meta, Configurações > Básico) | API e worker |
| `.whatsapp-webhook/verify-token` | um texto aleatório de 16 ou mais caracteres, o mesmo digitado na configuração do webhook na Meta | só a API |

- No Compose, as pastas são `${WHATSAPP_META_DIR:-./.whatsapp-meta}` e `${WHATSAPP_WEBHOOK_DIR:-./.whatsapp-webhook}`, montadas como leitura. Monta-se a pasta, nunca o arquivo, porque um arquivo ausente faria o Docker criar uma pasta no lugar. As duas são ignoradas pelo Git.
- Sem os arquivos, o webhook responde 503 e o resto da API funciona.
- Cada arquivo é lido de novo quando a data de modificação muda. Trocar o segredo na Meta e no arquivo não exige reiniciar a API.
- Os valores nunca aparecem em log, resposta ou `toString`.

## Conexão do número (CRM-081b)

Quem conecta: o Proprietário ou o Gestor da clínica (`CHANNEL_CONNECT`), ou o administrador da agência dona da carteira. Os outros papéis não veem o canal.

- **Rotas:**
  - clínica selecionada: `GET /v1/clinic/whatsapp/channel`, `POST …/connect` e `POST …/disconnect`;
  - agência: as mesmas em `/v1/agencies/{agencyId}/clinics/{tenantId}/whatsapp/channel`. Clínica fora da carteira, ou não ativa, responde 404 como se não existisse.
- **Situação devolvida:** `NONE`, `CONNECTING`, `ACTIVE` (com o número exibido), `FAILED` (com o código) ou `DISCONNECTING`. Nunca token, PIN, id da conta ou do número.
- **Conectar (`POST …/connect` com `{code, phoneNumberId?, coexistence?}`):**
  1. recusa logo se a clínica já tem canal vivo (`CHANNEL_ALREADY_CONNECTED`), antes de gastar o código;
  2. troca o código do Embedded Signup pelo token na hora, com o segredo do app no corpo da requisição;
  3. sela o token com a chave pública, grava o canal `PENDING` e a tarefa `VERIFY_ASSETS`, e responde 202. O código e o token nunca são gravados nem registrados em claro.
- **No worker, em etapas, com o token aberto só ali:**
  1. **`VERIFY_ASSETS`:** pelo próprio token (`debug_token`, que precisa ser deste app e ter os escopos de gestão e de mensagens na mesma conta) descobre as contas e lista os números. O `phoneNumberId` do navegador é só escolha entre eles. Sem escolha e com mais de um número, falha com `AMBIGUOUS_NUMBER`; número já usado por outra clínica, `NUMBER_IN_USE`;
  2. **`REGISTER_NUMBER`:** gera um PIN de 6 dígitos, sela token e PIN juntos com a chave mais nova e registra o número. Com `coexistence` (número que continua no app WhatsApp Business), essa etapa é pulada;
  3. **`SUBSCRIBE_WEBHOOKS`:** assina os webhooks da conta e ativa o canal.
- **Desconectar:** cria a tarefa `DISCONNECT`. O worker cancela a assinatura dos webhooks, a não ser que outro canal vivo use a mesma conta (uma agência com várias clínicas), apaga token e PIN e marca `DISCONNECTED`. É também o caminho para sair de `FAILED` e conectar de novo. Eventos que estavam na fila e chegam depois são descartados.
- **Falhas:**
  - Meta fora, limite de taxa ou chaves e segredo ausentes: nova tentativa, de 30 s até 8 min, no máximo 6;
  - recusa da Meta ou token revogado (erro 190): o canal fica `FAILED` com o código, por exemplo `TOKEN_INVALID`;
  - tentativas esgotadas: `FAILED` com o último código;
  - a desconexão acontece mesmo se a Meta não responder depois das tentativas;
  - uma tarefa cujo worker caiu volta depois de 2 minutos e gasta uma tentativa. Toda escrita confere o lease, então um worker atrasado nunca sobrescreve outro.
- **Chamadas à Meta:** cliente HTTP da própria JDK, com `appsecret_proof` em toda chamada com o token, sem redirecionamentos, e a versão da Graph API configurável (`app.whatsapp.meta.graph-version`, v25.0).
- **Configuração:** `WHATSAPP_APP_ID` no `.env` (não é segredo) e o arquivo `.whatsapp-meta/app-secret`. Sem eles, conectar responde 503 `CHANNEL_NOT_CONFIGURED`.
- **Riscos aceitos:**
  - a `coexistence` vem do evento do Embedded Signup e não é conferida pela Meta até o teste real: um `true` errado pula o registro, e um `false` errado faz o registro falhar com código;
  - o `debug_token` leva o token na query da URL, como a Graph API exige. A conexão é TLS direta, sem proxy com log de URL;
  - um canal `FAILED` mantém o token selado e o número reservado até alguém desconectar;
  - a ADR-036 pede a reação a `account_update` (bloqueio) e a recifragem na rotação das chaves: ficam para depois.

## Worker de entrada (CRM-082b)

O processo `whatsapp-worker` transforma os eventos gravados pelo webhook em contato, lead e mensagem.

- **Gatilho:** o `LISTEN whatsapp_inbound` e a varredura de 30 segundos só pedem uma drenagem. A drenagem roda numa thread só, canal por canal, em lotes de 200; pedidos que chegam durante uma drenagem viram mais uma passada.
- **Acesso:** a clínica sai do registro central (`openForChannel`), com canal e clínica ativos; nunca do payload.
- **Um evento, uma transação:**
  1. reserva o próximo evento devido, com `FOR UPDATE SKIP LOCKED`, na ordem do horário da Meta. Um evento de mensagem espera enquanto houver outro anterior do mesmo contato pendente e ainda tentável;
  2. trava o telefone canônico (advisory lock);
  3. se a mensagem já estiver gravada, não faz nada (reprocessamento manual seguro);
  4. cria o contato sem autor (`created_via = WHATSAPP`) ou reaproveita o existente;
  5. abre o lead em Novo com a origem detectada, se o contato não tiver lead aberto (RN-01). Com lead aberto e origem detectada, grava um toque de origem (RN-02);
  6. grava a conversa e a mensagem e marca o evento como processado.
- **Regras:**
  - **nome:** o do perfil do WhatsApp; sem ele, o próprio número. Um contato criado sem nome ganha o do perfil na primeira mensagem dele. Nome dado por uma pessoa nunca é trocado;
  - **telefone:** `PhoneNumber.parse`, com a regra do 9º dígito. Número recusado marca o evento como `INVALID_PHONE`, com aviso no log;
  - **eco do app WhatsApp Business:** contato e mensagem enviada (`sent_by_app`), nunca lead;
  - **contato arquivado:** a mensagem é gravada, sem lead (decisão do responsável, 21/11/2026). Para voltar a ser oportunidade, a equipe desarquiva o contato e abre o lead;
  - **status e eventos da conta:** marcados como processados com `DEFERRED_STATUS` e `DEFERRED_ACCOUNT`, sem efeito, até a CRM-084 e a CRM-081b.
- **Origem (CRM-091):**
  - `referral` de anúncio ou post impulsionado: Tráfego pago, com o id e o título do anúncio e o `ctwa_clid`. Valores inválidos são descartados um a um;
  - `[CODIGO]` no início do texto (letras, números, `_` e `-`, até 32): tipo Outro, fonte código. `[ig01]` e `[IG01]` são códigos diferentes;
  - nenhum dos dois: Não identificada.
- **Mensagem guardada:** tipo, texto (ou legenda, ou título do botão), sem NUL e com até 4096 caracteres, e o id e o tipo da mídia. O arquivo da mídia é baixado na CRM-084.
- **Falhas:**
  - **da clínica** (banco fora, migração pendente, identidade, segredo, permissão): o evento é adiado 30 segundos, sem gastar tentativa, e a passada daquela clínica para;
  - **do evento:** gasta uma tentativa, com espera de 30 s que dobra até 1 h, no máximo 8. Os outros contatos seguem;
  - o sinal `inbound_pending_since` só é limpo quando não resta evento tentável.
- **Evento esgotado:** fica guardado com o código do erro em `last_error` e um `ERROR` no log (`failed permanently`). Para reprocessar, como dono do banco da clínica, depois de corrigir a causa:
  ```sql
  UPDATE whatsapp_inbound_events SET attempts = 0, next_attempt_at = NULL
  WHERE processed_at IS NULL AND attempts >= 8;
  ```
  e marque o canal como pendente no banco central (`UPDATE whatsapp_channels SET inbound_pending_since = now() WHERE id = ...`). A mensagem já gravada não é duplicada.
- **Retenção:** uma vez por dia, o JSON bruto dos eventos processados há mais de 30 dias é apagado, em lotes de 1000. A linha fica, para a deduplicação. Só alcança clínicas e canais ativos.
- **Desempenho:** no teste local, do webhook ao lead leva bem menos de 5 segundos.

## Armazenamento de mídias (CRM-084b1)

- **Onde:** OCI Object Storage em São Paulo, pela API compatível com S3 (ADR-037), num bucket privado.
- **Configuração** (API e worker):
  - `WHATSAPP_MEDIA_BUCKET`: o nome do bucket;
  - `WHATSAPP_MEDIA_ENDPOINT`: `https://<namespace>.compat.objectstorage.sa-saopaulo-1.oraclecloud.com`;
  - `WHATSAPP_MEDIA_REGION`: `sa-saopaulo-1` (padrão);
  - a chave de acesso (uma Customer Secret Key da OCI) em dois arquivos na pasta `${WHATSAPP_STORAGE_DIR:-./.whatsapp-storage}`: `access-key-id` e `secret-access-key`. A pasta é montada como leitura e ignorada pelo Git; os valores nunca vão a variável de ambiente nem a log.
- **Sem configuração:** as mídias ficam pendentes, o link responde 503 e o resto funciona. Os arquivos de chave são procurados de novo a cada minuto.
- **Download pelo worker:** numa thread própria, com prazo total de 3 minutos por arquivo. O token vai só aos domínios de mídia da Meta (`*.fbsbx.com`, `*.whatsapp.net`, `*.facebook.com`, `*.fbcdn.net`), em https.
- **Riscos aceitos:** a mesma chave serve à API (que só gera links) e ao worker (que grava). Separar numa chave só de leitura para a API quando houver usuários distintos na OCI.

## Limites atuais

- O laço do worker (`LISTEN`, varredura e retenção) não tem teste automatizado; os testes chamam a drenagem diretamente.
- Clínica suspensa ou canal desconectado não passam pela retenção (CRM-073).
- A tela da conexão é a FE-009 (Codex). A conexão nunca foi feita com a Meta real: falta o ID de configuração do Embedded Signup, o app publicado e a URL pública.
- O webhook ainda não foi testado com a Meta real: falta a URL pública (CRM-072 ou túnel) e o app publicado.
- A subida do processo do worker não tem teste automatizado. O modo `whatsapp-worker` liga `spring.main.keep-alive`, porque sem HTTP só sobram threads daemon e a JVM sairia logo depois de subir. **A estabilidade do container ainda precisa ser conferida** com `docker compose up -d whatsapp-worker` e `docker compose ps` depois de alguns minutos.
