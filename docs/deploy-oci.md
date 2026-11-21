# Deploy na Oracle Cloud (OCI Always Free)

Data: 21/11/2026. Decisão: [ADR-037](adr/decisoes.md). Tarefa: CRM-072 em [TASKS.md](../TASKS.md).

Passo a passo para subir o backend numa VM da OCI em São Paulo, com o frontend na Vercel, **sem gerar cobrança**. Os limites da Oracle citados aqui foram conferidos em 21/11/2026 na página [Always Free Resources](https://docs.oracle.com/en-us/iaas/Content/FreeTier/resourceref.htm). Confira de novo antes de criar a conta, porque eles mudam.

## 0. Estado e escopo

- **Este roteiro serve para homologação com dados sintéticos.** Dados reais de clínicas só depois dos itens da seção 10 e de aprovação explícita (AGENTS.md §10).
- **Seções 1 a 6:** podem ser executadas agora. Conta, cotas, rede, VM, sistema e segredos.
- **Seção 7 em diante:** depende dos arquivos de produção listados em 7.1, que ainda não existem no repositório.
- **Quem faz o quê:**
  - **Responsável:** criar a conta, informar o cartão e cuidar do domínio. Agentes não criam contas nem digitam dados de pagamento.
  - **Agentes:** preparam os arquivos e o roteiro.

## 1. Como não gerar cobrança

O ponto principal: **o orçamento (Budget) da OCI só avisa, não bloqueia**. Ele é um limite "soft" e é avaliado só uma vez a cada 24 horas. O que impede gasto de verdade são três coisas, nesta ordem.

### 1.1 Conta Free Tier, sem upgrade

- **No cadastro:** a conta nasce com créditos de teste por 30 dias. Durante esse período, um recurso fora do gratuito consome crédito, não dinheiro.
- **Depois dos 30 dias:** a conta volta a ser só Always Free. Os recursos pagos param. **Não existe cobrança enquanto a conta não for convertida para Pay As You Go** por uma ação explícita do responsável.
- **Risco aceito na homologação:** a Oracle pode recuperar uma instância Always Free ociosa. A regra vale para um período de 7 dias, com CPU no percentil 95 abaixo de 20%, rede abaixo de 20% e, na A1, memória abaixo de 20%. Se acontecer, a VM é recriada a partir deste roteiro, e o backup protege os dados.
- **Converter para Pay As You Go** só quando o piloto não puder mais perder a VM, e **somente depois** das cotas da seção 1.2.

### 1.2 Cotas: o limite que de fato bloqueia

- **O que são:** cotas (Compartment Quotas) recusam a criação de qualquer recurso acima do limite. Elas valem em qualquer tipo de conta.
- **Onde criar:** em *Governance & Administration → Quota Policies*, no compartimento raiz (tenancy).
- **Sintaxe:** o padrão "zerar a família e liberar só o que interessa" segue o [exemplo oficial](https://docs.oracle.com/en-us/iaas/Content/Quotas/Concepts/sample_quotas.htm):

```text
zero compute-core quotas in tenancy
set compute-core quota standard-a1-core-count to 2 in tenancy
set compute-core quota standard-a1-core-regional-count to 2 in tenancy
set compute-core quota standard-e2-micro-core-count to 2 in tenancy
zero compute-memory quotas in tenancy
set compute-memory quota standard-a1-memory-count to 12 in tenancy
set compute-memory quota standard-a1-memory-regional-count to 12 in tenancy
set block-storage quota total-storage-gb to 200 in tenancy
set block-storage quota backup-count to 5 in tenancy
```

- **Política aplicada em 21/11/2026:** é a `always-free-only`, no compartimento raiz, e o bloco acima é o texto dela.
- **Variantes regionais:** a criação de uma A1 também consome `standard-a1-core-regional-count` e `standard-a1-memory-regional-count`. Sem as duas linhas `regional`, o `zero` recusa até a VM gratuita. Ao liberar outro formato, conferir se ele também tem variante regional.
- **Micro AMD:** ainda não testada. Se a VM de restauração (seção 9) for recusada, procurar a variante regional da micro na página [Compute Quotas](https://docs.oracle.com/iaas/Content/Quotas/Concepts/resourcequotas_topic-Compute_Quotas.htm).
- **Teste:** depois de salvar, tente criar uma VM de outro formato. A criação precisa ser recusada. Em 21/11/2026, a primeira versão, sem as linhas `regional`, recusou a A1, o que comprovou o bloqueio.

### 1.3 Orçamento como alarme

- **Onde:** em *Billing & Cost Management → Budgets*, um orçamento mensal de **US$ 1** no compartimento raiz.
- **Alertas:**
  - gasto **real** acima de 1% (US$ 0,01);
  - gasto **previsto** acima de 100%.
- **Destino:** o e-mail do responsável.
- **Lembrete:** como a avaliação é diária, o aviso pode chegar até um dia depois do gasto.

### 1.4 O que costuma gerar cobrança sem querer

| Item | Limite gratuito | Cuidado |
|---|---|---|
| VM | A1 Flex: 1.500 OCPU-hora e 9.000 GB-hora por mês, ou seja, **2 OCPUs e 12 GB** ligados o mês todo; até 2 micro AMD | Só criar formatos com a etiqueta *Always Free-eligible*, sempre na região inicial |
| Disco | **200 GB** somando volumes de boot e em bloco; 5 cópias de volume | O boot padrão tem 50 GB; não aumentar sem somar o total |
| Object Storage | 20 GB e 50 mil requisições por mês (10 GB por camada enquanto houver créditos ou com conta paga) | A retenção dos backups precisa caber em 10 GB. **Ao fim do teste, acima de 20 GB todos os objetos são apagados** |
| Tráfego de saída | 10 TB por mês | Sem risco no piloto |
| Balanceador | 1 flexível de 10 Mbps e 1 de rede | Não usar; o Nginx na VM basta |
| Outra região | Nenhum | Recursos fora da região inicial são cobrados |

### 1.5 Conferência mensal

- Em *Cost Analysis*, conferir que o mês fechou em US$ 0,00.
- **A primeira fatura zerada faz parte do aceite da CRM-072.**

## 2. Conta

1. Criar a conta em oracle.com/cloud/free com o e-mail do responsável.
2. **Região inicial (home region): Brazil East (São Paulo), `sa-saopaulo-1`.** Ela não pode ser trocada depois.
3. Ativar o MFA no usuário administrador.
4. Executar a seção 1 (cotas e orçamento) **antes** de criar qualquer recurso.
5. Criar o compartimento `branlycrm` e usá-lo para tudo o que vem a seguir.

## 3. Rede

1. *Networking → VCN Wizard → Create VCN with Internet Connectivity*, no compartimento `branlycrm`.
2. Na lista de segurança (ou NSG) da sub-rede pública, liberar entrada só para:
   - TCP 80 e 443 de `0.0.0.0/0`;
   - TCP 22 apenas do IP do operador (`<ip>/32`). Se o IP mudar com frequência, usar o OCI Bastion.
3. Nenhuma regra para 5432 ou 8080.

## 4. VM

1. *Compute → Create instance*:
   - **Imagem:** Ubuntu 24.04 (aarch64).
   - **Formato:** `VM.Standard.A1.Flex` com **2 OCPUs e 12 GB**, a cota inteira.
   - **Boot:** 50 GB, criptografado (padrão).
   - **Rede:** sub-rede pública com IP público.
   - **SSH:** chave pública do operador. A chave privada nunca vai para o repositório.
2. **"Out of host capacity":** São Paulo tem um único *availability domain*, então a saída é repetir mais tarde, de preferência fora do horário comercial.
   - A configuração está salva como o stack `branlycrm-api` em *Developer Services → Resource Manager → Stacks*, no compartimento `branlycrm`.
   - Cada nova tentativa é *Apply* nesse stack; o log do job mostra o motivo de uma falha.
   - Em 21/11/2026, as três primeiras tentativas falharam por falta de capacidade.
3. Registrar no DNS:
   - `A api.<domínio>` apontando para o IP público da VM;
   - o domínio do app (`app.<domínio>`) aponta para a Vercel.
4. Na homologação sem dados reais, dá para usar um subdomínio gratuito. O domínio próprio é obrigatório antes do webhook definitivo e do piloto (ADR-037).

## 5. Sistema operacional

```sh
sudo apt update && sudo apt -y full-upgrade
sudo apt -y install unattended-upgrades git
sudo dpkg-reconfigure -plow unattended-upgrades
```

- **Docker Engine:** instalar com o plugin Compose pelo repositório oficial da Docker para Ubuntu `arm64` e adicionar o usuário ao grupo `docker`.
- **Firewall da própria imagem:** as imagens Ubuntu da OCI vêm com regras `iptables` que só liberam SSH. Liberar 80 e 443 também na VM, antes do `REJECT`:

```sh
sudo iptables -I INPUT 6 -p tcp --dport 80 -m state --state NEW -j ACCEPT
sudo iptables -I INPUT 6 -p tcp --dport 443 -m state --state NEW -j ACCEPT
sudo netfilter-persistent save
```

- **Docker e o firewall:** o Docker cria as próprias regras. Por isso, nenhum serviço além do Nginx pode publicar porta no Compose de produção.

## 6. Código e segredos

1. **Código:** clonar o repositório com uma *deploy key* só de leitura, criada para esta VM.
2. **`.env`:** criar na raiz a partir de `.env.example`, com `chmod 600`.
   - Cada senha precisa ser **exclusiva e aleatória**, por exemplo com `openssl rand -base64 32`.
   - `MFA_ENCRYPTION_KEY` é gerada como indica o próprio `.env.example`.
   - O `.env` nunca sai da VM e nunca vai para log, ticket ou chat.
3. **Chaves do WhatsApp:**
   - gerar com `bash infra/whatsapp/generate-keys.sh`;
   - guardar a pasta `private/` também fora da VM, separada do backup do banco (ADR-036).
4. **Webhook:** criar a pasta de `WHATSAPP_WEBHOOK_DIR` com `app-secret` e `verify-token`, ambos com `chmod 600`.
5. **Valores de produção no `.env`:**
   - `APP_PUBLIC_ORIGIN=https://app.<domínio>`;
   - `SESSION_COOKIE_SECURE=true`;
   - `MAIL_PROVIDER=resend` e `RESEND_API_KEY`, se o e-mail estiver em uso;
   - o segredo do proxy, do item 7.1.

## 7. Subir o backend

### 7.1 Pendências no repositório (próximo passo da CRM-072)

O `compose.yaml` atual é o ambiente local: o Nginx escuta só em `127.0.0.1:8088` e sobe o Next.js. Para a VM, ainda falta:

- **`compose.oci.yaml` (override):**
  - sem o serviço `frontend`;
  - Nginx publicando 80 e 443;
  - certificados montados;
  - `SESSION_COOKIE_SECURE` repassado à API.
- **Configuração de produção do Nginx:**
  - redirecionar 80 para 443;
  - TLS com o certificado de `api.<domínio>`;
  - rota do webhook como está hoje;
  - **`/api/` só com o cabeçalho de segredo compartilhado** que a Vercel envia. Sem ele, responder 403;
  - `X-Forwarded-For` aceito só nessas requisições, e o do cliente nunca repassado como veio;
  - `/api/actuator/health` liberado sem o cabeçalho, só com o status, para o monitoramento externo;
  - nenhuma rota `/` para o Next.js.
- **Frontend na Vercel:** injetar o cabeçalho de segredo e o IP do cliente no encaminhamento de `/api/*`. O `rewrites()` do `next.config.ts` não adiciona cabeçalhos; avaliar o `proxy.ts`/middleware do Next com `NextResponse.rewrite(..., { request: { headers } })`.
- **API:** limitar o `forward-headers-strategy` ao proxy local, de forma que só o Nginx seja confiável (ADR-022).

Esses itens tocam autenticação e rede e passam por revisão de segurança antes de expor qualquer dado (AGENTS.md §9).

### 7.2 Primeira subida (depois de 7.1)

```sh
docker compose -f compose.yaml -f compose.oci.yaml build
docker compose -f compose.yaml -f compose.oci.yaml up -d --wait
docker compose -f compose.yaml -f compose.oci.yaml ps
```

- O build roda na própria VM e já gera imagens `arm64`. As imagens base são multiplataforma.
- **Saúde:** `curl -fsS https://api.<domínio>/api/actuator/health` responde `UP`. Qualquer outra rota de `/api/` sem o cabeçalho de segredo tem de responder 403.
- **Agência inicial:** criar com o comando `platform`, como está descrito no `compose.yaml`. O link de convite aparece só no terminal.

### 7.3 Certificado TLS

- Emitir com o Certbot (Let's Encrypt) pelo desafio HTTP-01 em `api.<domínio>`, com o Nginx servindo `/.well-known/acme-challenge/`.
- Renovação automática pelo timer do Certbot, recarregando o Nginx depois.
- Conferir que a validade aparece no monitoramento (seção 11).

## 8. Vercel e Meta

- **Vercel:**
  - `API_PROXY_TARGET=https://api.<domínio>` e o segredo do proxy nas variáveis do projeto, nunca no repositório;
  - domínio `app.<domínio>`;
  - plano Hobby só durante os testes; Pro antes de clínica pagante (ADR-022).
- **Meta:** callback do webhook em `https://api.<domínio>/api/v1/webhooks/whatsapp`, com o mesmo `verify-token` da VM.

## 9. Backup e restauração

- **Diário, por `cron` na VM:**
  1. `pg_dumpall --globals-only`, para os papéis;
  2. `pg_dump -Fc` do banco central e de cada banco de clínica;
  3. arquivo do volume `tenant_secrets`.
- **Criptografia:** antes do envio, com uma chave pública (`age` ou `gpg`). A chave privada de restauração fica fora da OCI.
- **Destino:**
  - um bucket privado no Object Storage de São Paulo, pela API compatível com S3, com uma chave de acesso exclusiva do backup;
  - ciclo de vida que apague cópias antigas, mantendo o total **abaixo de 10 GB**.
- **Chave privada do WhatsApp:** cópia separada, fora da VM e fora do bucket do banco.
- **Restauração isolada:**
  - Onde: a cota A1 inteira fica com a produção. A restauração vai para uma micro AMD Always Free, só com PostgreSQL e sem JVMs, ou é feita fora da OCI.
  - Como validar: recriar papéis e bancos, restaurar, e conferir contagens e o login do papel runtime de uma clínica.
  - **Isso é o aceite da CRM-072.**

## 10. Antes de dados reais (piloto)

- [ ] Itens de 7.1 implementados e com revisão de segurança.
- [ ] Domínio próprio e webhook definitivo.
- [ ] Backup diário funcionando e restauração testada.
- [ ] Conta convertida para Pay As You Go, **com as cotas da seção 1.2 ativas**, para a VM não ser recuperada.
- [ ] Revisão de privacidade (CRM-073) e aprovação para dados reais.
- [ ] Vercel Pro, se já houver clínica pagante.

## 11. Operação

- **Atualizações:** de segurança automáticas no sistema; atualização das imagens a cada deploy.
- **Deploy:** seguir a ordem de ADR-031 (`docs/provisionamento-clinicas.md`): parar o provisionador antigo → `migrate` → provisionador novo → `tenant-migrate` → API nova. O código de saída 1 do `tenant-migrate` interrompe o deploy.
- **Monitoramento mínimo:**
  - verificação externa gratuita de `https://api.<domínio>/api/actuator/health`;
  - alerta de disco acima de 80%;
  - alerta de validade do certificado;
  - e-mail do orçamento da OCI.
- **Recuperação:** com a VM perdida, recriar pelas seções 3 a 7 e restaurar pela seção 9.
