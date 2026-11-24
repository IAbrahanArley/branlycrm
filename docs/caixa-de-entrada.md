# Caixa de entrada (CRM-083), respostas (CRM-084a) e mídias recebidas (CRM-084b1)

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

## Regras

- **Assumir é atômico:** um único `UPDATE … WHERE assignee_id IS NULL`. Dois atendentes clicando juntos: um assume, o outro recebe `CONVERSATION_TAKEN`. Assumir de novo a própria conversa não muda nada.
- **Soltar, transferir e marcar** usam a `version` lida; uma mudança no meio responde `CONVERSATION_STALE`. Transferir para quem já atende e marcar o que já está marcado não geram evento.
- **Mensagem nova não muda a situação:** a conversa em atendimento continua com o responsável; a sem responsável continua aguardando. Não há "encerrar" no MVP.
- **Ordem das mensagens:** a ordem de chegada (`seq`, na migração de clínica V16), não o horário da Meta. O horário da Meta tem precisão de segundos e uma mensagem pode chegar atrasada (nova tentativa). Com a ordem de chegada, o cursor `after` nunca pula uma mensagem. O horário da Meta (`sentAt`) é só para mostrar.
- **Auditoria:** cada mudança (assumir, soltar, transferir, marcar, desmarcar) grava um evento em `whatsapp_conversation_events`, com o autor.
- **Contato arquivado:** a conversa continua na caixa, com `contactArchived`, e as mensagens dele continuam chegando (sem lead, decisão de 21/11/2026).
- **Responsável que saiu da equipe:** a conversa continua com ele. O Proprietário ou o Gestor solta ou transfere; a tela cruza o `assigneeId` com a equipe ativa para mostrar isso.

## Fora do escopo

- Enviar mídia (CRM-084b2), modelos e o limite diário (CRM-085), respostas rápidas (CRM-086).
- "Não lida" por pessoa.
- Soltar automaticamente as conversas de quem sai da equipe.
