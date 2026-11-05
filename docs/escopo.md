# Escopo funcional do MVP

Consolidado em 05/11/2026 a partir das decisões da conversa.

## Objetivo e jornada

CRM SaaS específico para clínicas de estética: captar contatos desde o tráfego, atender pelo WhatsApp com apoio de IA, qualificar, agendar, registrar comparecimento e acompanhar a venda e o relacionamento.

Jornada: anúncio → contato → atendimento → qualificação → agendamento → comparecimento → venda → retorno.

## Limites iniciais

Várias clínicas com isolamento de dados. Uma unidade e um número de WhatsApp por clínica no MVP, com estrutura preparada para expansão. Interface web adaptável a computador e celular. Sem prontuário, diagnóstico, prescrição, RH, estoque, comissões, emissão fiscal, financeiro completo, aplicativo nativo, gestão de anúncios ou campanhas em massa.

## Acesso e equipe

Login por e-mail/senha, verificação de e-mail, convite com validade, recuperação de senha, encerramento e revogação de sessão, proteção contra tentativas repetidas e segundo fator para administradores. Provedor e detalhes ainda serão escolhidos.

Cadastro público cria clínica e administrador; funcionários entram por convite. Separar identidade, participação na clínica e profissional com agenda. Um profissional pode existir sem login e uma recepcionista pode ter login sem ser profissional. Papéis: administrador, recepção/comercial e profissional. Administrador da plataforma tem autorização própria e acesso auditado.

Desativação preserva autoria e histórico, revoga acesso e permite redistribuir pendências. Não apagar silenciosamente agendamentos futuros.

## Operação da clínica

Configurar endereço, contatos, fuso, horários, equipe, profissionais, serviços, duração, salas e equipamentos. Configurar conexão WhatsApp, conhecimento e limites da IA.

## CRM e captação

Contatos com nome, telefone, origem, interesses, etiquetas, responsável e histórico. Entrada por cadastro manual, formulário integrado e WhatsApp. Registrar campanha e anúncio quando disponíveis, sem prometer atribuição completa. Deduplicar telefone dentro da clínica, preservando histórico.

Oportunidades com responsável, serviço, valor estimado, próxima ação e motivo de perda. Funil inicial: novo contato → em atendimento → qualificado → avaliação agendada → avaliação realizada → ganho ou perdido. Agendamento e presença não significam venda automática.

## WhatsApp

Caixa compartilhada, histórico, texto e anexos suportados, atribuição, notas internas, situação de envio e falhas. Acesso ao contexto do contato, oportunidade e agenda. Respeitar regras do canal e modelos aprovados quando necessários.

Estados: IA atendendo → aguardando equipe → humano atendendo → encerrado. Assumir atendimento interrompe respostas automáticas pendentes da IA.

## IA e conhecimento

IA comercial consulta conhecimento aprovado por clínica e dados operacionais, coleta interesse, qualifica e pode criar reserva após confirmação do cliente. RAG com documentos e textos versionados, aprovação, processamento e desativação. Histórico de conversa não vira conhecimento automaticamente.

Separar busca de conhecimento, leitura operacional e ferramentas de ação. Preço vigente e disponibilidade são consultados nos registros operacionais. Só confirmar reserva após sucesso da operação.

Transferir por pedido de pessoa, ausência ou conflito de informação, negociação fora das regras, reclamação, falha recorrente ou assunto que exija avaliação clínica. Entregar resumo e motivo. Fora do horário, manter pendência e informar expectativa configurada. IA não realiza diagnóstico, prescrição ou indicação clínica individual.

## Agenda própria

Visões diária e semanal, filtros por profissional e situação. Reserva com contato, serviço, profissional, data, duração, fuso, recursos, observações, origem e histórico. MVP com um serviço e um profissional por reserva.

Horários de trabalho, pausas, bloqueios e exceções. Impedir conflito simultâneo de profissional, sala e equipamento tanto para equipe quanto IA. Estados: agendado, confirmado, compareceu, faltou e cancelado. Equipe pode reagendar/cancelar; IA encaminha essas solicitações ao humano no MVP.

## Tarefas, lembretes e indicadores

Tarefas com responsável e vencimento; notificações de espera; lembretes de agenda com controle de repetição e falha.

Painel por período: contatos por origem, primeira resposta, uso da IA e transferências, agendamentos, cancelamentos, faltas, comparecimentos, ganhos, perdas e valor vendido registrado. Investimento em mídia pode ser informado manualmente; não calcular retorno ou custo de aquisição sem dados suficientes.

## Comercial

Direção definida: mensalidade por clínica/unidade com usuários incluídos e franquia de IA. Unidade comercial inicial: contato atendido pela IA no mês, contado uma vez por clínica no período. Medir custos reais internamente. Valores, franquias, excedentes, apuração, cobrança automática e tratamento do custo WhatsApp continuam pendentes. Sem excedente autorizado, limite atingido encaminha novas demandas ao humano.

## Telas previstas

Acesso/recuperação; configuração inicial; painel; caixa de entrada; contatos; oportunidades/funil; agenda; tarefas; conhecimento/IA; serviços/profissionais/recursos; equipe/permissões; integrações; plano/consumo; administração da plataforma.

Prioridade de prototipação: caixa de entrada e agenda. Lovable foi oferecido pelo usuário como opção; nenhuma integração ou protótipo foi realizado até aqui.

## Validação

Detalhes verificáveis e exceções estão em cenarios-e-aceitacao.md. O piloto exige o fluxo completo, isolamento, concorrência de reservas, revogação de acesso, repetição de eventos e falhas de provedores verificados.
