# Entregas e decisões pendentes

Data: 04/11/2026.

## Sequência proposta

| Entrega | Resultado verificável | Cenários |
|---|---|---|
| 1 — Acesso e operação humana | Clínica isolada, equipe, contatos, funil e agenda funcionando | SC-001 a 008; SC-014 |
| 2 — Atendimento WhatsApp | Mensagens, responsáveis, controle humano e falhas rastreáveis | SC-009; partes humanas de SC-012 |
| 3 — IA comercial | Conhecimento aprovado, qualificação, reserva e transferência | SC-010 a 012 |
| 4 — Piloto | Lembretes, consumo, indicadores, operação e cobrança definida | SC-013 a 016 |

Isolamento, auditoria e confiabilidade são construídos em cada entrega; a entrega 4 consolida a validação operacional.

## Decisões necessárias antes da implementação correspondente

| Tema | O que falta decidir |
|---|---|
| Autenticação | Provedor ou implementação, sessões, validade de convites e recuperação, implantação e recuperação de segundo fator |
| Permissões | Matriz detalhada de leitura/escrita e informações visíveis ao profissional |
| WhatsApp | Integração direta ou provedor, número piloto, cadastro e modelos de mensagens |
| IA | Provedor, modelos, embeddings, critérios de avaliação, limites e custo esperado |
| Conhecimento | Formatos e tamanho de documentos, responsável por aprovação, critérios para resposta e transferência |
| Agenda | Políticas de antecedência, cancelamento, intervalos e tratamento de conflitos ao mudar disponibilidade |
| Operação | Hospedagem, armazenamento, atualização da interface, ferramentas de monitoramento e metas de recuperação |
| Dados | Retenção, exclusão, exportação e acessos administrativos |
| Comercial | Preços, franquias, excedentes, custos do WhatsApp, período/fuso de apuração e provedor de pagamento |
| Atribuição | Primeiro conjunto de formulários e fontes de tráfego suportados |

## Propostas novas a validar

- Revogar sessões anteriores após recuperação de senha: SC-002/AC-03.
- Reabrir conversa encerrada como aguardando equipe, sem retomar IA automaticamente: SC-012/AC-05.
- Contabilizar contato na franquia apenas após resposta efetivamente enviada pela IA: SC-015/AC-01.

## Provas técnicas prioritárias

1. Receber evento do WhatsApp e enviar resposta, incluindo duplicidade e timeout.
2. Recuperar conteúdo de uma clínica sem retornar conteúdo de outra.
3. Disputar o mesmo horário por duas requisições e confirmar apenas uma reserva.
4. Assumir conversa enquanto uma resposta da IA está pendente e impedir o envio pendente.

## Interface

Prototipar primeiro caixa de entrada e agenda, com estados vazios, carregamento, erro, permissão negada, conflito de horário e transferência humana. Lovable pode apoiar essa etapa, conforme disponibilidade do conector. Nenhuma interface foi gerada nesta fase documental.

## Condição de entrada no piloto

Fluxo completo validado: captar → atender → agendar → transferir quando necessário → lembrar → registrar presença → registrar resultado. Validar também todos os casos de isolamento, concorrência, revogação de acesso, duplicidade e falha de provedores previstos nos ACs. Pendências comerciais e operacionais aplicáveis ao piloto devem estar resolvidas.

## Atualização em 05/11/2026

- Frontend inicial criado e movido para o repositório correto; ver projeto.md.
- Documentação consolidada em docs/.
- Build de produção do frontend validado no Docker; publicação e configuração de produção continuam pendentes.
- Backend Spring Boot 4.1.2 identificado em backend/ e integrado ao Compose; a pasta api/ adicional não foi reorganizada.
- Modelagem de dados, matriz de permissões, contratos detalhados e protótipos ainda não foram realizados.
- GitHub Project e Lovable não foram utilizados.
- Compatibilidade de Swagger refinada: Boot 4.0.x com springdoc 3.0.x; Boot 4.1.x com springdoc 3.1.x–3.2.x, conforme consulta oficial registrada na conversa. Para Boot 4.1.x foi exemplificado springdoc 3.2.0. Não foi confirmada a versão exata instalada do backend.


## Próxima entrega definida

Plano em plano-onboarding.md: separar credenciais, detalhar cadastro, verificar e-mail, provisionar banco por clínica, ativar após validação e integrar telas. Itens do plano ainda não foram implementados. Acesso atual documentado em autenticacao.md.
