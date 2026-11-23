# Caixa de entrada (CRM-083)

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

## Regras

- **Assumir é atômico:** um único `UPDATE … WHERE assignee_id IS NULL`. Dois atendentes clicando juntos: um assume, o outro recebe `CONVERSATION_TAKEN`. Assumir de novo a própria conversa não muda nada.
- **Soltar, transferir e marcar** usam a `version` lida; uma mudança no meio responde `CONVERSATION_STALE`. Transferir para quem já atende e marcar o que já está marcado não geram evento.
- **Mensagem nova não muda a situação:** a conversa em atendimento continua com o responsável; a sem responsável continua aguardando. Não há "encerrar" no MVP.
- **Ordem das mensagens:** a ordem de chegada (`seq`, na migração de clínica V16), não o horário da Meta. O horário da Meta tem precisão de segundos e uma mensagem pode chegar atrasada (nova tentativa). Com a ordem de chegada, o cursor `after` nunca pula uma mensagem. O horário da Meta (`sentAt`) é só para mostrar.
- **Auditoria:** cada mudança (assumir, soltar, transferir, marcar, desmarcar) grava um evento em `whatsapp_conversation_events`, com o autor.
- **Contato arquivado:** a conversa continua na caixa, com `contactArchived`, e as mensagens dele continuam chegando (sem lead, decisão de 21/11/2026).
- **Responsável que saiu da equipe:** a conversa continua com ele. O Proprietário ou o Gestor solta ou transfere; a tela cruza o `assigneeId` com a equipe ativa para mostrar isso.

## Fora do escopo

- Responder (CRM-084), a janela de 24 horas e os modelos (CRM-085), respostas rápidas (CRM-086).
- "Não lida" por pessoa.
- Mensagens na linha do tempo do contato e cards de conversas no dashboard.
- Soltar automaticamente as conversas de quem sai da equipe.
