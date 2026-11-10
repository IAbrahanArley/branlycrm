# Papéis e permissões por clínica

Referência para o RBAC do servidor e para os testes de permissão de cada módulo.

- **Catálogo vigente** (ADR-025, CRM-017, migração V7): Gestor (`OWNER`, `MANAGER`), Atendente (`ATTENDANT`) e Somente leitura (`VIEWER`), mais os níveis de agência e plataforma (ADR-026), que ainda não estão no código (CRM-018).
- **Catálogo anterior** (ADR-020, CRM-012): os cinco papéis, mantidos abaixo só como histórico. As regras invariantes e as garantias continuam valendo.

## Catálogo vigente (ADR-025)

| Papel no banco | Nome na interface | Responsabilidade |
|---|---|---|
| `OWNER` | Gestor (proprietário) | Responde pela clínica: dados cadastrais, plano, equipe completa, exportação e auditoria |
| `MANAGER` | Gestor | Opera o comercial: funil, etapas, motivos de perda, procedimentos, respostas rápidas, equipe operacional, relatórios e exportação |
| `ATTENDANT` | Atendente | Atende pela caixa de entrada, cadastra contatos, move leads, define temperatura, cria tarefas e registra vendas e perdas |
| `VIEWER` | Somente leitura | Consulta contatos, leads, conversas, dashboard e relatório, sem alterar nada |

Legenda:
- **✓**: permitido em todos os registros da clínica selecionada.
- **Si**: só para si mesmo.
- **—**: sem acesso.

O escopo "próprios" da matriz anterior não existe aqui: a caixa de entrada é compartilhada.

| Recurso / ação | OWNER | MANAGER | ATTENDANT | VIEWER |
|---|---|---|---|---|
| Dados da clínica e fuso: editar | ✓ | — | — | — |
| Dados da clínica e fuso: ver | ✓ | ✓ | ✓ | ✓ |
| Plano e assinatura | ✓ | — | — | — |
| Equipe: ver | ✓ | ✓ | — | — |
| Equipe: convidar, mudar papel, desativar | ✓ | Só `ATTENDANT` e `VIEWER` | — | — |
| Configuração comercial (funil, etapas, motivos de perda, procedimentos, profissionais, respostas rápidas) | ✓ | ✓ | — | — |
| Canal WhatsApp: ver estado | ✓ | ✓ | — | — |
| Contatos: ver e buscar | ✓ | ✓ | ✓ | ✓ |
| Contatos: criar, editar, criar e vincular tags, consentimento | ✓ | ✓ | ✓ | — |
| Contatos: arquivar e desarquivar | ✓ | ✓ | — | — |
| Tags: renomear e excluir do catálogo (`TAG_MANAGE`) | ✓ | ✓ | — | — |
| Contatos: importar planilha | ✓ | ✓ | — | — |
| Leads: ver, Kanban e linha do tempo | ✓ | ✓ | ✓ | ✓ |
| Leads: criar, mover etapa, temperatura, notas | ✓ | ✓ | ✓ | — |
| Leads: assumir como responsável | ✓ | ✓ | Si | — |
| Leads: atribuir ou reatribuir a outro | ✓ | ✓ | — | — |
| Leads: corrigir origem manualmente | ✓ | ✓ | ✓ | — |
| Vendas e perdas: registrar | ✓ | ✓ | ✓ | — |
| Vendas: corrigir ou cancelar registro | ✓ | ✓ | — | — |
| Caixa de entrada: ler conversas | ✓ | ✓ | ✓ | ✓ |
| Caixa de entrada: responder, assumir, enviar mídia e template | ✓ | ✓ | ✓ | — |
| Tarefas: criar e atribuir | ✓ | ✓ | ✓ | — |
| Tarefas: ver | ✓ | ✓ | ✓ | ✓ |
| Notificações próprias | ✓ | ✓ | ✓ | ✓ |
| Lista de leads com filtros | ✓ | ✓ | ✓ | ✓ |
| Lista de leads: exportar (Excel, CSV, PDF) | ✓ | ✓ | — | — |
| Dashboard da recepção | ✓ | ✓ | ✓ | ✓ |
| Relatório mensal | ✓ | ✓ | — | ✓ |
| Auditoria da clínica | ✓ | — | — | — |

**Conversão feita pela V7 (CRM-017):**
- `RECEPTION` e `SALES` → `ATTENDANT`;
- `PROFESSIONAL` → `VIEWER`;
- `OWNER` e `MANAGER` continuam.

### Agência e plataforma (ADR-026)

Não são papéis de clínica e nunca aparecem em `tenant_memberships`.

| Ação | Admin da agência | Super Admin |
|---|---|---|
| Ver clínicas (nome, situação, plano, estado do canal) | Só da própria carteira | Todas |
| Criar clínica | Na própria carteira | Direta ou em qualquer agência |
| Convidar o primeiro gestor da clínica | Na própria carteira | ✓ |
| Conectar ou trocar o número do WhatsApp | Na própria carteira | ✓ |
| Criar e suspender agências | — | ✓ |
| Ler contatos, leads, conversas e vendas | Só com participação na clínica | Não, por padrão (suporte auditado é fluxo próprio, futuro) |
| Painel consolidado com agregados, sem dados pessoais (F2) | Da própria carteira | ✓ |

**Regras:**
- **2FA:** obrigatório para Admin da agência, Super Admin e `OWNER`.
- **Recusa:** clínica de outra agência e clínica inexistente recebem a mesma resposta.

## Catálogo anterior (ADR-020, histórico)

Substituído pela V7. Os nomes `RECEPTION`, `SALES` e `PROFESSIONAL` são recusados pelo banco e pela API.

### Papéis

Cada usuário tem **um** papel por clínica (`tenant_memberships`), e pode ter papéis diferentes em clínicas diferentes. Papéis nunca são autoridade global na sessão.

| Papel no banco | Nome na interface | Responsabilidade |
|---|---|---|
| `OWNER` | Proprietário | Responde pela clínica: dados cadastrais, plano, equipe completa, exportação de dados e auditoria. Vê tudo da clínica. |
| `MANAGER` | Gerente | Opera o comercial: funis, etapas, procedimentos, distribuição de oportunidades, metas e relatórios. Gerencia a equipe operacional, sem mexer em proprietários e gerentes. |
| `RECEPTION` | Recepcionista | Porta de entrada: cadastra contatos, cria oportunidades, agenda e confirma avaliações e cuida das tarefas de atendimento. Não fecha vendas. |
| `SALES` | Vendedor | Conduz negociações: trabalha as oportunidades atribuídas a ele, registra atividades, move etapas e fecha como ganha ou perdida. |
| `PROFESSIONAL` | Profissional | Atende avaliações: vê a própria agenda e registra comparecimento ou falta. Não acessa o funil nem relatórios. |

**Super Admin** é autorização da plataforma, fora de `tenant_memberships`. Não acessa dados comerciais das clínicas por padrão (planejamento, seção 2.1).

### Matriz por módulo

Legenda:
- **T**: todos os registros da clínica.
- **P**: só os próprios, ou seja, atribuídos ao usuário, criados por ele ou vinculados a oportunidades e agendamentos dele.
- **—**: sem acesso.

| Recurso / ação | OWNER | MANAGER | RECEPTION | SALES | PROFESSIONAL |
|---|---|---|---|---|---|
| Dados da clínica, fuso, horários | Editar | Ver | Ver | Ver | Ver |
| Plano e assinatura | Ver | — | — | — | — |
| Equipe: ver membros | T | T | — | — | — |
| Equipe: convidar, mudar papel, desativar | T | Só RECEPTION, SALES e PROFESSIONAL | — | — | — |
| Funis, etapas, procedimentos | Editar | Editar | Ver | Ver | — |
| Contatos: criar | ✓ | ✓ | ✓ | ✓ (fica responsável) | — |
| Contatos: ver | T | T | T | P | — |
| Contatos: ver dados básicos (nome e telefone) | T | T | T | P | P |
| Contatos: editar | T | T | T | P | — |
| Contatos: arquivar | T | T | T | — | — |
| Oportunidades: criar | ✓ | ✓ | ✓ | ✓ | — |
| Oportunidades: ver | T | T | T | P | — |
| Oportunidades: mover etapa e editar valor | T | T | — | P | — |
| Oportunidades: ganhar ou perder | T | T | — | P | — |
| Oportunidades: escolher responsável ao criar | T | T | T | — (fica responsável) | — |
| Oportunidades: reatribuir responsável | T | T | — | — | — |
| Atividades e notas | T | T | T | P | — |
| Tarefas: criar para si | ✓ | ✓ | ✓ | ✓ | ✓ |
| Tarefas: atribuir a outros | ✓ | ✓ | ✓ | — | — |
| Tarefas: ver | T | T | P | P | P |
| Agenda: criar, confirmar, reagendar, cancelar | T | T | T | P | — |
| Agenda: ver | T | T | T | P | P |
| Agenda: registrar comparecimento ou falta | T | T | T | — | P |
| Dashboard e relatórios | T | T | — | Só o próprio desempenho | — |
| Exportação de dados | ✓ | — | — | — | — |
| Auditoria da clínica | Ver | — | — | — | — |

## Regras invariantes

A matriz do catálogo vigente é executável em `tenant/domain/ClinicRole` (uma linha por `ClinicPermission`) e testada linha a linha. Mudar uma exige mudar a outra.

- Toda clínica ACTIVE tem pelo menos um `OWNER` ativo. Não é possível remover, rebaixar ou desativar o último, nem ativar uma clínica sem proprietário. O serviço confere isso sob lock, e os triggers da V6 são a última barreira. Só conta como proprietário quem tem participação ativa e conta ativa e verificada, a mesma condição usada para autorizar acesso. Transferir a propriedade é feito promovendo outro membro antes de rebaixar o atual.
- Desativar a conta do último proprietário (`user_accounts.active`) é operação da plataforma, fora deste fluxo, e ainda não é bloqueada.
- O provisionador cria a participação do administrador da clínica como `OWNER`.
- `MANAGER` não pode criar, promover, rebaixar ou desativar `OWNER` nem `MANAGER`; só gerencia `ATTENDANT` e `VIEWER`. Ninguém altera o próprio papel.
- "Si" (`OWN`) é sempre calculado no servidor, a partir do `subject_id` do principal autenticado. Um responsável indicado pelo cliente é validado contra participação ativa na clínica.
- Recusa por papel e recusa por recurso de outro usuário respondem igual a recurso inexistente quando o recurso é identificado por ID, sem revelar que ele existe.
- Exceção na gestão de equipe: quem pode ver a equipe já vê os membros. Recusa sobre um membro visível (MANAGER sobre OWNER, alterar a si mesmo) responde 403. Alvo que não é membro ativo da clínica selecionada responde 404, inclusive membro inativo: reativar é feito por convite (CRM-013).
- Endpoints de clínica usam sempre a clínica selecionada na sessão, nunca um identificador de clínica na URL. Sem seleção, a resposta é 409.
- Ações de equipe, fechamento de oportunidade, exportação e mudanças de configuração entram na auditoria (CRM-070).

## Garantias e limites da implementação

- **Ordem de travas:** toda escrita em `tenant_memberships` trava primeiro a clínica (`FOR NO KEY UPDATE` em `tenants`) e depois os membros, com `lock_timeout` de 5 s, que vira 503 com `Retry-After`. Convites (CRM-013) e qualquer escritor futuro devem seguir a mesma ordem, para não haver deadlock.
- **Permissões do banco:** o papel runtime só pode alterar `role` e `active` das participações e não pode apagá-las (V6). As funções dos triggers fixam o `search_path`, para que uma tabela temporária não engane a proteção do último proprietário.
- **Pendências conhecidas:**
  - Desativar contas pela plataforma ao mesmo tempo que se rebaixa um proprietário pode deixar a clínica sem proprietário efetivo. Hoje não existe rota para isso; o fluxo de desativação de contas precisa travar a clínica do mesmo jeito.
  - A lista de equipe mostra o e-mail de ex-membros inativos a OWNER e MANAGER. Revisar na CRM-073 (LGPD).
  - A auditoria das ações de equipe depende da CRM-070.

## Endpoints de equipe (CRM-012)

| Rota | Uso |
|---|---|
| `GET /v1/auth/clinic` | Clínica selecionada, papel e escopo de cada permissão; é só uma dica para a interface |
| `GET /v1/clinic/team` | Membros da clínica selecionada (`TEAM_VIEW`) |
| `PUT /v1/clinic/team/{subjectId}/role` | Muda o papel (`TEAM_MANAGE` + regras acima) |
| `DELETE /v1/clinic/team/{subjectId}` | Desativa o membro sem apagar o histórico |
| `GET /v1/clinic/team/invitations` | Convites pendentes da clínica selecionada (`TEAM_MANAGE`), vencidos inclusive |
| `POST /v1/clinic/team/invitations` | Convida ou reemite o convite pendente do e-mail; `MANAGER` só para `ATTENDANT` e `VIEWER` |
| `POST /v1/clinic/team/invitations/{id}/resend` | Novo token e validade; o link anterior para de funcionar |
| `DELETE /v1/clinic/team/invitations/{id}` | Revoga; convite de outra clínica responde 404 |
| `GET /v1/agencies` | Agências ativas que o usuário administra |
| `GET /v1/agencies/{agencyId}/clinics` | Carteira da agência: só dados do registro (nome, situação, estado do convite do gestor), nunca dados de pacientes |
| `POST /v1/agencies/{agencyId}/clinics` | Cria clínica na carteira com `requestId` idempotente; a clínica nasce `PROVISIONING` e o gestor recebe convite |
| `POST /v1/agencies/{agencyId}/clinics/{tenantId}/owner-invitation` | Reemite o convite do gestor enquanto a clínica nunca teve dono |
| `POST /v1/invitations/preview` e `/accept` | Públicas, com CSRF e limite de tentativas ([autenticacao.md](autenticacao.md)) |

## Migração do catálogo novo (V7)

- **Conversão:** `RECEPTION` e `SALES` viram `ATTENDANT`; `PROFESSIONAL` vira `VIEWER`. `OWNER` e `MANAGER` não mudam, então os triggers de último proprietário não disparam.
- **Mais leitura para ex-profissionais:** o `PROFESSIONAL` via só a própria agenda e os dados básicos desses contatos. Como `VIEWER`, passa a ler contatos, leads, conversas e o relatório da clínica toda quando esses módulos existirem. Em 06/11/2026 não havia nenhum ambiente com dados reais (o deploy é CRM-072), então nenhuma pessoa real ganhou acesso. Ambientes futuros já nascem no catálogo novo.
- **Mais exportação para gerentes:** `MANAGER` ganha `DATA_EXPORT`, conforme o documento do cliente. Cada exportação será auditada (CRM-053, CRM-070).
- **Ordem de deploy:** aplicar a V7 (`migrate`) antes de subir a API nova e não manter API antiga e nova ao mesmo tempo. Uma API que lê um papel que não conhece responde 500 `INTERNAL_ERROR` em toda autorização: a falha é fechada, mas derruba o acesso.

## Migração do catálogo (V6)

- **Catálogo:** a V6 trocou a constraint para os cinco papéis e converteu `ADMIN` em `OWNER`, conferindo que toda clínica ACTIVE ficou com proprietário.
- **Triggers:** criou os triggers de último proprietário e de ativação.
- **Permissões do runtime:** reduziu a permissão do papel runtime sobre participações a `UPDATE (role, active)`, sem `DELETE`.
- **Provisionador:** passa a criar `OWNER` e recusa ativar a clínica se a participação existente do administrador não for de proprietário ativo (`ADMIN_NOT_OWNER`).
- **Escopo P:** o cálculo de "próprios" é implementado em cada módulo de negócio, com teste A/B e de papel.
