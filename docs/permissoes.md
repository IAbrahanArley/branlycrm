# Papéis e permissões por clínica

Decidido em 05/11/2026 (ADR-020). Implementação: CRM-012. Este documento é a referência para o RBAC do servidor e para os testes de permissão de cada módulo.

## Papéis

Cada usuário tem **um** papel por clínica (`tenant_memberships`), e pode ter papéis diferentes em clínicas diferentes. Papéis nunca são autoridade global na sessão.

| Papel no banco | Nome na interface | Responsabilidade |
|---|---|---|
| `OWNER` | Proprietário | Responde pela clínica: dados cadastrais, plano, equipe completa, exportação de dados e auditoria. Vê tudo da clínica. |
| `MANAGER` | Gerente | Opera o comercial: funis, etapas, procedimentos, distribuição de oportunidades, metas e relatórios. Gerencia a equipe operacional, sem mexer em proprietários e gerentes. |
| `RECEPTION` | Recepcionista | Porta de entrada: cadastra contatos, cria oportunidades, agenda e confirma avaliações e cuida das tarefas de atendimento. Não fecha vendas. |
| `SALES` | Vendedor | Conduz negociações: trabalha as oportunidades atribuídas a ele, registra atividades, move etapas e fecha como ganha ou perdida. |
| `PROFESSIONAL` | Profissional | Atende avaliações: vê a própria agenda e registra comparecimento ou falta. Não acessa o funil nem relatórios. |

**Super Admin** é autorização da plataforma, fora de `tenant_memberships`. Não acessa dados comerciais das clínicas por padrão (planejamento, seção 2.1).

## Matriz por módulo

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

A matriz acima é executável em `tenant/domain/ClinicRole` (uma linha por `ClinicPermission`) e testada linha a linha. Mudar uma exige mudar a outra.

- Toda clínica ACTIVE tem pelo menos um `OWNER` ativo. Não é possível remover, rebaixar ou desativar o último, nem ativar uma clínica sem proprietário. O serviço confere isso sob lock, e os triggers da V6 são a última barreira. Só conta como proprietário quem tem participação ativa e conta ativa e verificada, a mesma condição usada para autorizar acesso. Transferir a propriedade é feito promovendo outro membro antes de rebaixar o atual.
- Desativar a conta do último proprietário (`user_accounts.active`) é operação da plataforma, fora deste fluxo, e ainda não é bloqueada.
- O provisionador cria a participação do administrador da clínica como `OWNER`.
- `MANAGER` não pode criar, promover, rebaixar ou desativar `OWNER` nem `MANAGER`. Ninguém altera o próprio papel.
- "Próprios" (P) é sempre calculado no servidor, a partir do `subject_id` do principal autenticado. Um responsável indicado pelo cliente é validado contra participação ativa na clínica.
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

## Migração do catálogo (V6)

- **Catálogo:** a V6 trocou a constraint para os cinco papéis e converteu `ADMIN` em `OWNER`, conferindo que toda clínica ACTIVE ficou com proprietário.
- **Triggers:** criou os triggers de último proprietário e de ativação.
- **Permissões do runtime:** reduziu a permissão do papel runtime sobre participações a `UPDATE (role, active)`, sem `DELETE`.
- **Provisionador:** passa a criar `OWNER` e recusa ativar a clínica se a participação existente do administrador não for de proprietário ativo (`ADMIN_NOT_OWNER`).
- **Escopo P:** o cálculo de "próprios" é implementado em cada módulo de negócio, com teste A/B e de papel.
