# Canal WhatsApp: base (CRM-081a)

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

## Limites atuais

- O worker só descobre os canais com eventos pendentes: pelo `LISTEN whatsapp_inbound` numa conexão própria e por uma varredura a cada 30 segundos. O processamento vem na CRM-082b.
- O webhook (CRM-082a) e a conexão pelo Embedded Signup (CRM-081b) ainda não existem.
- A subida do processo do worker não tem teste automatizado. O modo `whatsapp-worker` liga `spring.main.keep-alive`, porque sem HTTP só sobram threads daemon e a JVM sairia logo depois de subir. **A estabilidade do container ainda precisa ser conferida** com `docker compose up -d whatsapp-worker` e `docker compose ps` depois de alguns minutos.
