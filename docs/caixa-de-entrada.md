# Caixa de entrada (CRM-083), respostas (CRM-084a), mídias (CRM-084b) e modelos (CRM-085)

Implementada em 22/11/2026. As conversas e mensagens são gravadas pelo worker de entrada ([whatsapp-canal.md](whatsapp-canal.md)); a caixa de entrada as lê e controla quem atende cada conversa.

## Quem faz o quê

| Ação | Permissão | Papéis |
|---|---|---|
| Ver a lista, a conversa e o histórico | `INBOX_READ` | todos, inclusive Somente leitura |
| Assumir, soltar a própria conversa, marcar "precisa de humano" | `INBOX_REPLY` | Proprietário, Gestor, Atendente |
| Transferir para outra pessoa, soltar a conversa de um colega | `INBOX_ASSIGN` | Proprietário, Gestor |

O novo responsável precisa ser membro ativo que atende conversas (`INBOX_REPLY`).

## Rotas (`/v1/clinic/inbox/conversations`)

- `GET /`: lista paginada, da conversa mais recente para a mais antiga. Filtros `status` (`WAITING`, `IN_SERVICE`), `assignee` (`mine`, `none`) e `needsHuman` (`true`, `false`). Cada item traz:
  - contato (nome e telefone) e se ele está arquivado (`contactArchived`);
  - situação, responsável e "precisa de humano";
  - a prévia da última mensagem (até 120 caracteres) e `awaitingReply`, quando a última mensagem é do paciente;
  - `version`, para as alterações.
- `GET /{id}`: a conversa.
- `GET /{id}/messages`: o histórico, do mais antigo para o mais recente, em fatias de até 100 (padrão 50):
  - sem cursor: as últimas mensagens;
  - `before`: as anteriores, para "carregar anteriores";
  - `after`: só as novas, para a consulta de 5 segundos. Sem novidades, devolve o mesmo `after`.
- `POST /{id}/take`: assumir.
- `POST /{id}/release` com `{version}`: soltar; a conversa volta a aguardar.
- `PUT /{id}/assignee` com `{version, assigneeId}`: transferir.
- `PUT /{id}/needs-human` com `{version, needsHuman}`: marcar ou desmarcar.

Os erros estão em [api-convencoes.md](api-convencoes.md) (`CONVERSATION_*`).

## Responder (CRM-084a)

- `POST /{id}/messages` com `{idempotencyKey, text}` (`INBOX_REPLY`): responde 202 com a resposta na fila. O texto tem de 1 a 4096 caracteres; quebras de linha são mantidas.
  - A `idempotencyKey` é um UUID gerado pela tela para cada envio. O mesmo pedido repetido (por exemplo, depois de uma queda de rede) devolve a mesma resposta e nunca enfileira duas. A mesma chave em outra conversa recebe `CONVERSATION_IDEMPOTENCY_CONFLICT`.
  - **Quem responde assume:** sem responsável, quem responde passa a atender. Com outra pessoa atendendo, só o Proprietário e o Gestor (`INBOX_ASSIGN`) respondem; o Atendente recebe `CONVERSATION_TAKEN`.
  - **Janela de 24 horas:** se a última mensagem do paciente tem mais de 24 horas, `CONVERSATION_WINDOW_CLOSED`. Fora da janela só valem modelos aprovados (CRM-085). O worker confere de novo antes de enviar.
- `GET /{id}/outbox`: as respostas que ainda pedem atenção, da mais nova para a mais antiga: na fila, enviando, incertas e as que falharam nos últimos 7 dias.
- **Situações de uma resposta:**

| Situação | Significado |
|---|---|
| `QUEUED` | Na fila; o worker envia em segundos |
| `SENDING` | Sendo enviada |
| `SENT`, `DELIVERED`, `READ` | Aceita pela Meta, entregue e lida (pelos webhooks de status); a mensagem já está na conversa |
| `FAILED` | Recusada, com `errorCode`, por exemplo `META_131047` (janela fechada) ou `WINDOW_CLOSED` |
| `UNCERTAIN` | Pode ter chegado ao paciente: houve tempo esgotado ou a conexão caiu depois do pedido. **Nunca é reenviada sozinha** (ADR-005). A tela pede para conferir no WhatsApp; reenviar cria um pedido novo, com chave nova |

- **Como o worker envia:** pega uma resposta por vez, com uma reserva de 2 minutos, e chama a Graph API com `biz_opaque_callback_data` igual ao id da resposta.
  - Aceita: `SENT`, e a mensagem entra na conversa com a situação de entrega.
  - Não chegou à Meta (conexão recusada, limite de taxa): nova tentativa, até 5.
  - Pode ter chegado (tempo esgotado, conexão perdida, erro 5xx): `UNCERTAIN`.
  - Se o worker cair no meio de um envio, a reserva vence e a resposta vira `UNCERTAIN`.
- **Conciliação pelos status:** cada status de entrega acha a resposta pelo id que a Meta devolve, ou pelo `wamid`. A situação só avança (enviada, entregue, lida); `failed` vence. Uma resposta `UNCERTAIN` que a Meta diz ter enviado vira `SENT`, e a mensagem entra na conversa. Um status que chega antes de a resposta estar gravada é tentado de novo depois.

## Mídias recebidas (CRM-084b1)

- Cada mensagem traz `mediaStatus`:
  - `PENDING`: sendo baixada;
  - `STORED`: o arquivo está disponível;
  - `REJECTED`: tipo ou tamanho não aceito;
  - `FAILED`: não foi possível baixar;
  - nulo: mensagem sem mídia.
- `GET /{id}/messages/{messageId}/media` (`INBOX_READ`, todos os papéis): devolve `{url, contentType, expiresAt}`, um link de 5 minutos que baixa o arquivo como anexo, com o tipo detectado.
  - Peça um link novo a cada clique; não guarde nem compartilhe o link.
  - Erros: `CONVERSATION_MEDIA_NOT_READY` (ainda baixando), `CONVERSATION_MEDIA_REJECTED`, `CONVERSATION_MEDIA_NOT_FOUND` e `CONVERSATION_MEDIA_UNAVAILABLE` (503).
- **Tipos aceitos**, conferidos pelos primeiros bytes do arquivo e nunca pelo tipo declarado:
  - imagens JPEG, PNG e WebP, até 5 MB;
  - vídeos MP4 e 3GP, até 16 MB;
  - áudios OGG/Opus, MP3, AAC, M4A e AMR, até 16 MB;
  - documentos PDF, DOCX, XLSX e PPTX, até 100 MB.
  - Qualquer outro arquivo (executável, HTML, SVG, ZIP comum...) fica `REJECTED`, e a mensagem continua na conversa com a legenda.
- **Armazenamento:** bucket privado no OCI Object Storage em São Paulo (API compatível com S3, ADR-037), com a chave `clinics/{clínica}/media/{id}`. A chave sai sempre do banco da clínica, nunca do pedido. Os arquivos podem mostrar dados de saúde e seguem a retenção do contato (CRM-073).

## Enviar arquivos (CRM-084b2)

- `POST /{id}/messages/media` (`INBOX_REPLY`), em `multipart/form-data`:
  - `file`: o arquivo;
  - `idempotencyKey`: um UUID gerado pela tela para cada envio, como no texto;
  - `caption` (opcional): legenda de até 1024 caracteres. Áudio não tem legenda: com legenda, `INVALID_INPUT` em `caption`.
- Responde 202 com o item na fila, como a resposta em texto, com `kind` `MEDIA`, `contentType` (detectado), `fileName` (só documentos) e `text` (a legenda, ou nulo). As respostas em texto vêm com `kind` `TEXT`.
- **Mesmas regras do texto:** quem responde assume, a janela de 24 horas, a mesma chave devolve o mesmo envio e as mesmas situações (`QUEUED`, `SENT`, `UNCERTAIN`...). A chave repetida não guarda o arquivo de novo.
- **Tipos aceitos**, pelos primeiros bytes do arquivo (nunca pelo nome ou pelo tipo declarado):
  - imagens JPEG e PNG, até 5 MB (WebP é só figurinha no WhatsApp e é recusado);
  - vídeos MP4 e 3GP, até 16 MB;
  - áudios MP3, M4A, OGG, AAC e AMR, até 16 MB (vão como arquivo de áudio, não como mensagem de voz);
  - documentos PDF, DOCX, XLSX e PPTX, até 16 MB.
- **Erros:** `CONVERSATION_MEDIA_TYPE_NOT_ALLOWED` (415), `CONVERSATION_MEDIA_TOO_LARGE` (413), `CONVERSATION_MEDIA_UNAVAILABLE` (503, armazenamento fora) e os do texto. Acima de 17 MB o pedido é cortado antes de chegar à regra: `FILE_TOO_LARGE` (413) na API, ou um 413 do Nginx.
- **Nome do documento:** o paciente vê o nome enviado, sem pastas, aspas nem caracteres de controle, com no máximo 120 caracteres e terminando na extensão do tipo detectado. Sem nome, `documento.pdf` (ou a extensão do tipo).
- **Como funciona:**
  1. a API confere tudo antes de gravar (permissão, conversa, posse, janela e chave), lê o arquivo do disco temporário, detecta o tipo e grava no bucket em `clinics/{clínica}/media/{id}`;
  2. no banco da clínica, numa só transação, cria a mídia (`OUT`, `STORED`) e o item da fila. Se a regra recusar nesse meio-tempo, o arquivo é apagado do bucket;
  3. o worker baixa o arquivo do bucket, sobe para a Meta (`/{phone_number_id}/media`) e guarda o id da Meta, que vale para as novas tentativas sem subir de novo;
  4. envia a mensagem `image`, `video`, `audio` ou `document` com esse id, a legenda e o nome. Daí em diante vale o texto: incerto nunca é reenviado;
  5. enviada, a mídia aponta para a mensagem. No histórico ela aparece como as recebidas (`type`, `mediaStatus` `STORED`, `mediaMime`) e o link de download funciona igual.
- Falha ao subir para a Meta não é envio: conexão ou limite de taxa tentam de novo (até 5 vezes); recusa da Meta vira `FAILED` com o código.
- O worker sobe um arquivo por canal a cada passada e volta às mensagens recebidas antes do próximo. A reserva de um envio é de 6 minutos, para cobrir a leitura do bucket, a subida (até 2 minutos) e o envio.
- A conferência de tipo é a mesma das mídias recebidas. Alguns arquivos passam por ela e a Meta recusa (por exemplo OGG sem Opus, MP4 só com áudio): o envio fica `FAILED` com o código da Meta.

## Modelos (CRM-085)

Fora da janela de 24 horas, só modelos aprovados pela Meta podem ser enviados (WPP-06). Os modelos são criados e aprovados no WhatsApp Manager da Meta; o CRM só os copia e envia.

- **Cópia dos modelos:** o worker lê os modelos da conta do número conectado e guarda uma cópia no banco da clínica:
  - logo depois de conectar;
  - a cada 6 horas;
  - quando a Meta avisa pelo webhook que um modelo mudou (aprovado, pausado, desativado);
  - quando o Proprietário ou o Gestor pede (`POST /v1/clinic/inbox/templates/sync`, `CHANNEL_CONNECT`, 202). Em até 30 segundos o worker copia de novo.
  - Uma cópia que falha é tentada de novo em 30 minutos. Modelo apagado na Meta fica `DELETED` e some da lista.
- `GET /v1/clinic/inbox/templates` (`INBOX_READ`): os modelos, primeiro os que podem ser enviados. Cada um traz `status` (da Meta), `supported`, `sendable`, os textos (`headerText`, `bodyText`, `footerText`) e as chaves das variáveis em ordem (`headerParams`, `bodyParams`: `"1"`, `"2"`... ou nomes).
- **Suportados no MVP:** cabeçalho de texto (ou nenhum), corpo com variáveis, rodapé e botões sem variável (resposta rápida, telefone, link fixo). Cabeçalho com imagem, vídeo ou documento, botão com variável e modelos de autenticação aparecem com `supported: false`.
- `POST /v1/clinic/inbox/conversations/{id}/messages/template` (`INBOX_REPLY`) com `{idempotencyKey, templateId, header, body}`. `header` e `body` são objetos com o valor de cada chave, por exemplo `{"1": "Ana", "2": "amanhã"}`.
  - Vale dentro e fora da janela, com as outras regras do texto: quem responde assume e a mesma chave nunca gera dois envios.
  - Responde 202 com o item na fila (`kind` `TEMPLATE`), e `text` traz o modelo como o paciente vai ver. É esse texto que fica no histórico, com `type` `template`.
  - **Regras da Meta para os valores:** todos preenchidos, sem chave a mais, sem quebra de linha, tabulação ou mais de quatro espaços seguidos; até 1024 caracteres no corpo e 60 no cabeçalho. Fora disso, `INVALID_INPUT` com o campo `body.CHAVE` ou `header.CHAVE`.
  - **Marketing:** recusado (`CONVERSATION_TEMPLATE_OPTED_OUT`) quando o último consentimento de WhatsApp do contato é `OPT_OUT`. Modelos de utilidade continuam permitidos.
  - **Limite diário:** 200 modelos por clínica por dia, contados no fuso da clínica e ao enfileirar (configuração `app.whatsapp.templates.daily-limit`). Acima dele, `CONVERSATION_TEMPLATE_LIMIT` (429) e nada entra na fila. Um envio recusado depois não devolve a vaga.
  - Erros: `CONVERSATION_TEMPLATE_NOT_FOUND` (404), `CONVERSATION_TEMPLATE_NOT_APPROVED` (409, não aprovado ou não suportado), `CONVERSATION_TEMPLATE_OPTED_OUT` (409), `CONVERSATION_TEMPLATE_LIMIT` (429) e os do texto. Na sincronização sem número conectado, `CONVERSATION_CHANNEL_NOT_CONNECTED` (409).
- **Worker:** antes de enviar, confere de novo se o modelo continua aprovado na última cópia; se foi pausado ou desativado, a resposta fica `FAILED` com `TEMPLATE_NOT_APPROVED`. O envio segue as regras do texto (incerto nunca reenviado).
- **Pagamento:** modelos são cobrados pela Meta na conta da clínica. Sem meio de pagamento, a Meta recusa e a resposta fica `FAILED` com o código dela (por exemplo `META_131042`); a tela deve explicar.

## Regras

- **Assumir é atômico:** um único `UPDATE … WHERE assignee_id IS NULL`. Dois atendentes clicando juntos: um assume, o outro recebe `CONVERSATION_TAKEN`. Assumir de novo a própria conversa não muda nada.
- **Soltar, transferir e marcar** usam a `version` lida; uma mudança no meio responde `CONVERSATION_STALE`. Transferir para quem já atende e marcar o que já está marcado não geram evento.
- **Mensagem nova não muda a situação:** a conversa em atendimento continua com o responsável; a sem responsável continua aguardando. Não há "encerrar" no MVP.
- **Ordem das mensagens:** a ordem de chegada (`seq`, na migração de clínica V16), não o horário da Meta. O horário da Meta tem precisão de segundos e uma mensagem pode chegar atrasada (nova tentativa). Com a ordem de chegada, o cursor `after` nunca pula uma mensagem. O horário da Meta (`sentAt`) é só para mostrar.
- **Auditoria:** cada mudança (assumir, soltar, transferir, marcar, desmarcar) grava um evento em `whatsapp_conversation_events`, com o autor.
- **Contato arquivado:** a conversa continua na caixa, com `contactArchived`, e as mensagens dele continuam chegando (sem lead, decisão de 21/11/2026).
- **Responsável que saiu da equipe:** a conversa continua com ele. O Proprietário ou o Gestor solta ou transfere; a tela cruza o `assigneeId` com a equipe ativa para mostrar isso.

## Fora do escopo

- Respostas rápidas (CRM-086).
- Criar ou editar modelos pelo CRM (WPP-10, Fase 2), modelos com mídia no cabeçalho ou botão com variável, e limite diário por clínica (hoje é um valor global).
- Figurinhas, mensagem de voz gravada na tela e documentos acima de 16 MB.
- "Não lida" por pessoa.
- Soltar automaticamente as conversas de quem sai da equipe.
