# Canal WhatsApp: base (CRM-081a) e webhook (CRM-082a)

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

| Arquivo | Conteúdo |
|---|---|
| `app-secret` | o segredo do app (Meta, Configurações > Básico) |
| `verify-token` | um texto aleatório de 16 ou mais caracteres, o mesmo digitado na configuração do webhook na Meta |

- No Compose, ficam na pasta `${WHATSAPP_WEBHOOK_DIR:-./.whatsapp-webhook}`, montada só na API, como leitura. A pasta é ignorada pelo Git.
- Sem os arquivos, o webhook responde 503 e o resto da API funciona.
- Cada arquivo é lido de novo quando a data de modificação muda. Trocar o segredo na Meta e no arquivo não exige reiniciar a API.
- Os valores nunca aparecem em log, resposta ou `toString`.

## Limites atuais

- O worker só descobre os canais com eventos pendentes: pelo `LISTEN whatsapp_inbound` numa conexão própria e por uma varredura a cada 30 segundos. O processamento vem na CRM-082b. Ao terminar um canal, o worker precisa limpar `inbound_pending_since` e conferir de novo se chegou evento, porque o webhook só marca o sinal quando ele está vazio.
- A conexão pelo Embedded Signup (CRM-081b) ainda não existe.
- O webhook ainda não foi testado com a Meta real: falta a URL pública (CRM-072 ou túnel) e o app publicado.
- A subida do processo do worker não tem teste automatizado. O modo `whatsapp-worker` liga `spring.main.keep-alive`, porque sem HTTP só sobram threads daemon e a JVM sairia logo depois de subir. **A estabilidade do container ainda precisa ser conferida** com `docker compose up -d whatsapp-worker` e `docker compose ps` depois de alguns minutos.
