# Cenários e critérios de aceitação

Data: 04/11/2026. Status: especificação inicial, ainda não implementada.

Cada AC é identificado pelo cenário e número: SC-001/AC-01, por exemplo. Os critérios orientam validação funcional e testes de integração quando a implementação existir.

## SC-001 — Criar clínica e acessar a conta

**Ator:** Administrador. **ADRs:** 002, 003.

- **AC-01:** Dado um e-mail ainda não cadastrado, quando o cadastro válido for concluído e o e-mail verificado, então existirão uma clínica, sua unidade inicial e a participação de administrador vinculada à identidade.
- **AC-02:** Dadas credenciais válidas e os fatores exigidos, quando o usuário entrar, então acessará apenas suas clínicas autorizadas.
- **AC-03:** Dadas credenciais inválidas ou tentativas repetidas, quando houver tentativa de acesso, então o sistema negará a entrada, aplicará proteção contra repetição e não revelará se o e-mail existe.
- **AC-04:** Dado um cadastro repetido, quando solicitado, então não criará identidades duplicadas e oferecerá o fluxo de acesso ou recuperação sem revelar a existência da conta a terceiros.

## SC-002 — Recuperar acesso e encerrar sessão

**Ator:** Usuário. **ADR:** 003.

- **AC-01:** Dado um pedido de recuperação, quando enviado, então a resposta pública será neutra; apenas o titular receberá um mecanismo de recuperação com prazo e uso único.
- **AC-02:** Dado um mecanismo vencido ou já utilizado, quando apresentado, então a senha não será alterada.
- **AC-03:** Dada recuperação concluída, quando a nova senha for definida, então sessões anteriores serão revogadas. Essa política é uma proposta de segurança a confirmar na escolha da autenticação.
- **AC-04:** Dada uma sessão ativa, quando o usuário sair, então a sessão encerrada não permitirá novas operações protegidas.
- **AC-05:** Dado um administrador com segundo fator habilitado, quando houver login sem esse fator, então o acesso será negado; recuperação do segundo fator terá fluxo verificado próprio.

## SC-003 — Convidar equipe e aplicar permissões

**Ator:** Administrador. **ADRs:** 002, 003.

- **AC-01:** Dado um convite válido, quando o destinatário o aceitar autenticado com a identidade correspondente, então receberá a participação e o papel definidos naquela clínica.
- **AC-02:** Dado um convite vencido, revogado ou já usado, quando alguém tentar aceitá-lo, então nenhum novo acesso será concedido.
- **AC-03:** Dado um profissional com acesso apenas à própria agenda, quando tentar consultar configurações, cobrança ou agenda alheia, então o backend negará a operação.
- **AC-04:** Dado um usuário da clínica A, quando tentar acessar identificadores, arquivos, buscas ou eventos da clínica B, então nenhum dado da clínica B será retornado ou alterado.
- **AC-05:** Dado um usuário com duas participações, quando mudar de clínica, então seus dados e permissões refletirão exclusivamente o contexto selecionado e autorizado.

## SC-004 — Cadastrar profissional e desligar funcionário

**Ator:** Administrador. **ADRs:** 003, 004.

- **AC-01:** Dado um profissional sem login, quando cadastrado com serviços e disponibilidade, então poderá receber reservas sem ganhar acesso ao sistema.
- **AC-02:** Dada uma recepcionista sem cadastro profissional, quando convidada, então poderá operar conforme seu papel sem aparecer como prestadora de serviços.
- **AC-03:** Dada uma participação desativada, quando a pessoa tentar nova operação, inclusive com sessão anterior, então o acesso àquela clínica será negado e sua autoria histórica preservada.
- **AC-04:** Dado um desligamento com conversas e tarefas pendentes, quando confirmado, então o sistema apresentará as pendências e permitirá reatribuição explícita.
- **AC-05:** Dado um profissional inativado, quando houver agendamentos futuros, então eles não serão apagados silenciosamente; serão sinalizados para resolução e novas reservas serão impedidas.

## SC-005 — Configurar disponibilidade

**Ator:** Administrador. **ADR:** 004.

- **AC-01:** Dados serviço, duração, profissional, horários e recursos necessários, quando a agenda for consultada, então apenas intervalos inteiramente válidos estarão disponíveis.
- **AC-02:** Dados pausas, bloqueios e exceções, quando coincidirem com um intervalo, então o horário não será oferecido.
- **AC-03:** Dado um bloqueio que conflita com reservas existentes, quando for criado, então o sistema identificará os conflitos e exigirá resolução explícita, preservando as reservas existentes até lá.
- **AC-04:** Dado o fuso da unidade, quando um agendamento for visualizado, então o horário será apresentado nesse fuso de forma consistente, inclusive em mudanças de data ou deslocamento de fuso.

## SC-006 — Agendar com concorrência

**Ator:** Recepção ou IA autorizada. **ADR:** 004.

- **AC-01:** Dado um intervalo disponível, quando a reserva válida for confirmada no banco, então o sistema retornará identificador e detalhes do agendamento.
- **AC-02:** Dadas duas requisições simultâneas para um recurso incompatível no mesmo período, quando processadas, então apenas uma poderá confirmar; a outra receberá conflito e poderá consultar alternativas.
- **AC-03:** Dada uma repetição da mesma solicitação identificada, quando reprocessada, então retornará o resultado existente sem criar outra reserva.
- **AC-04:** Dada uma falha ao persistir, quando ocorrer, então nem a interface nem a IA informarão reserva confirmada.

## SC-007 — Reagendar, cancelar e registrar presença

**Ator:** Recepção ou usuário autorizado. **ADR:** 004.

- **AC-01:** Dada uma reserva existente, quando reagendada, então a mudança será atômica: se o novo horário falhar, o anterior permanecerá preservado.
- **AC-02:** Dado cancelamento autorizado, quando concluído, então o horário será liberado e autor, data e motivo ficarão no histórico.
- **AC-03:** Dada uma reserva, quando registrada presença ou falta, então a situação será atualizada sem registrar venda automaticamente.
- **AC-04:** Dado cancelamento ou reagendamento, quando houver lembretes pendentes, então os antigos serão invalidados e os novos corresponderão à situação atual.

## SC-008 — Captar contato e atribuir origem

**Ator:** Integração ou recepção. **ADRs:** 002, 005.

- **AC-01:** Dado um contato recebido com origem válida, quando cadastrado, então a origem e os identificadores disponíveis de campanha serão preservados.
- **AC-02:** Dado um evento repetido, quando recebido, então não criará contato ou oportunidade duplicados por reprocessamento.
- **AC-03:** Dado telefone normalizado já existente na mesma clínica, quando identificado, então a interação será associada ao contato existente, preservando o histórico de origem.
- **AC-04:** Dado telefone existente em outra clínica, quando recebido, então não haverá acesso, fusão ou associação entre as duas clínicas.
- **AC-05:** Dado um contato sem atribuição disponível, quando criado, então será marcado como origem não identificada, sem inventar anúncio ou campanha.

## SC-009 — Atender pelo WhatsApp

**Ator:** Recepção e integração. **ADRs:** 005, 007.

- **AC-01:** Dado evento autenticado do canal, quando recebido, então a mensagem ficará vinculada à clínica, contato e conversa corretos.
- **AC-02:** Dado um evento inválido ou de integração desconhecida, quando recebido, então não criará dados nem acionará a IA.
- **AC-03:** Dado envio autorizado, quando realizado, então seu estado acompanhará os retornos do provedor sem regredir por evento atrasado.
- **AC-04:** Dada restrição do canal, quando o envio livre não for permitido, então o sistema indicará a condição e utilizará apenas alternativa autorizada.
- **AC-05:** Dado timeout com resultado incerto, quando processado, então ficará pendente de reconciliação em vez de provocar reenvios indiscriminados.

## SC-010 — Publicar conhecimento e responder com RAG

**Ator:** Administrador e IA. **ADRs:** 002, 006.

- **AC-01:** Dado documento novo, quando enviado, então haverá estado de processamento e eventual falha; somente conteúdo aprovado e ativo ficará disponível à IA.
- **AC-02:** Dada pergunta respondível pela base aprovada, quando atendida, então a resposta respeitará o conteúdo recuperado e as referências usadas ficarão registradas internamente.
- **AC-03:** Dada informação ausente ou conflitante, quando consultada, então a IA não inventará resposta e encaminhará a demanda à equipe.
- **AC-04:** Dado documento de outra clínica ou versão desativada, quando houver busca, então ele não será incluído no contexto da resposta.
- **AC-05:** Dado conteúdo que instrui a ignorar regras, quando recuperado ou enviado pelo cliente, então não modificará permissões nem liberará ferramentas restritas.

## SC-011 — Qualificar e agendar pela IA

**Ator:** Cliente e IA. **ADRs:** 004, 006.

- **AC-01:** Dado interesse comercial, quando identificado, então a IA registrará informações explicitamente fornecidas, sem transformar suposição em dado confirmado.
- **AC-02:** Dado pedido de preço ou disponibilidade, quando respondido, então a IA consultará os dados operacionais vigentes.
- **AC-03:** Dado horário escolhido e confirmação do cliente, quando a ferramenta concluir a reserva, então a IA comunicará os detalhes confirmados.
- **AC-04:** Dado conflito ou falha, quando a ferramenta negar a reserva, então a IA oferecerá alternativas ou transferirá sem afirmar que agendou.
- **AC-05:** Dado pedido de reagendamento ou cancelamento, quando recebido no MVP, então encaminhará à equipe sem executar a alteração.

## SC-012 — Transferir para humano

**Ator:** Cliente, IA e recepção. **ADR:** 007.

- **AC-01:** Dado pedido explícito de pessoa ou outro gatilho definido, quando detectado, então a conversa passará a aguardando equipe com motivo, resumo e notificação.
- **AC-02:** Dado atendimento aguardando equipe, quando alguém assumir, então o estado será humano atendendo e a IA deixará de enviar respostas automáticas.
- **AC-03:** Dada resposta de IA ainda na fila, quando o controle mudar antes do envio, então a resposta será invalidada na revalidação de estado.
- **AC-04:** Dada transferência fora do horário, quando ocorrer, então a pendência será mantida e o cliente receberá a expectativa configurada, sem promessa de atendimento imediato.
- **AC-05:** Dada conversa humana ou encerrada, quando novas mensagens chegarem, então não haverá retomada automática da IA; uma ação autorizada poderá devolvê-la à IA. Regra operacional proposta: nova mensagem após encerramento reabre como aguardando equipe.

## SC-013 — Executar tarefas e lembretes

**Ator:** Equipe e worker. **ADRs:** 005, 009.

- **AC-01:** Dada tarefa com responsável e vencimento, quando criada, então ficará visível para os usuários autorizados e poderá ser concluída com histórico.
- **AC-02:** Dado lembrete elegível, quando chegar o momento e a reserva continuar válida, então será enviado conforme regras do canal.
- **AC-03:** Dada repetição do processamento, quando ocorrer, então o mesmo lembrete lógico não será novamente submetido após confirmação de envio.
- **AC-04:** Dada falha definitiva após as tentativas configuradas, quando atingida, então ficará visível para acompanhamento, sem ciclo infinito de tentativas.

## SC-014 — Acompanhar funil e resultados

**Ator:** Recepção e administrador. **ADRs:** 001, 002.

- **AC-01:** Dada oportunidade, quando sua etapa mudar, então o histórico registrará alteração e responsável.
- **AC-02:** Dado agendamento ou comparecimento, quando registrado, então atualizará o contexto comercial sem marcar automaticamente a oportunidade como ganha.
- **AC-03:** Dada venda registrada, quando a oportunidade for ganha, então valor e data comporão os indicadores; perda exigirá motivo.
- **AC-04:** Dado filtro de período, quando aplicado, então contatos e vendas usarão suas respectivas datas de entrada e fechamento; agendamentos e comparecimentos usarão a data do atendimento. Essas definições ficarão visíveis no painel.
- **AC-05:** Dado tempo de primeira resposta, quando calculado, então medirá o intervalo entre a primeira mensagem recebida do atendimento e a primeira resposta enviada, distinguindo IA e humano.
- **AC-06:** Dada ausência de investimento ou atribuição, quando exibidos resultados, então o painel não apresentará custo de aquisição ou retorno como se fossem conhecidos.

## SC-015 — Medir consumo e aplicar limites

**Ator:** Administrador e sistema. **ADRs:** 002, 008.

- **AC-01:** Dado contato com resposta efetivamente enviada pela IA, quando apurado no período, então contará uma vez para a franquia da clínica, mesmo com múltiplas conversas. Essa definição de evento faturável é proposta para validação comercial.
- **AC-02:** Dado reprocessamento, quando ocorrer, então não duplicará o consumo comercial; custos operacionais reais continuarão registrados separadamente.
- **AC-03:** Dado limite atingido sem excedente autorizado, quando nova demanda de IA chegar, então encaminhará ao atendimento humano e notificará a clínica.
- **AC-04:** Dado acesso ao painel de consumo, quando consultado, então exibirá período, franquia, uso e regra de excedentes aplicável.
- **AC-05:** Dado uso simultâneo próximo ao limite, quando processado, então a autorização de consumo será consistente e não liberará uso ilimitado por concorrência.

## SC-016 — Operar, auditar e recuperar

**Ator:** Operação autorizada. **ADR:** 009.

- **AC-01:** Dada alteração de permissão, reserva ou ação relevante da IA, quando concluída, então haverá registro auditável de autor, clínica, instante e resultado.
- **AC-02:** Dada falha de integração, quando ocorrer, então será possível correlacionar evento e execução sem expor credenciais nos logs.
- **AC-03:** Dado backup de ambiente de teste, quando restaurado, então a consistência dos dados será verificada e o resultado documentado antes do piloto.
- **AC-04:** Dada migração de banco, quando aplicada em ambiente de validação, então será reproduzível e terá procedimento documentado de recuperação em caso de falha.
- **AC-05:** Dado acesso administrativo da plataforma, quando utilizado, então exigirá autorização própria e produzirá trilha de auditoria; não haverá consulta irrestrita implícita aos dados clínicos.

## Revisão de isolamento — 05/11/2026

ADR-014 substitui a estratégia de banco compartilhado. SC-003/AC-04 deve ser validado entre bancos distintos. SC-017 e SC-018 estão detalhados em multitenancy.md e incluem requisitos ainda não implementados.

## Entrega parcial de acesso — 05/11/2026

SC-001: login implementado, cadastro/verificação/MFA pendentes. SC-002: logout implementado, recuperação pendente. SC-003: participação, seleção e validação implementadas; convite e permissões granulares pendentes. Ver autenticacao.md para protocolo e testes. Não considerar os cenários completos.
