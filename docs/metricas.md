# Métricas do dashboard e do relatório mensal (CRM-050)

> Aprovado pelo responsável em 11/11/2026. Atende REL-01 e REL-02. Implementação: CRM-051 (API) e CRM-052 (telas).
> Valores de venda são **faturamento registrado**: o que a equipe lançou no sistema. Nunca são chamados de receita recebida, porque o pagamento não passa pelo sistema.

## Regras gerais

- **Período por acontecimento.** Cada número conta o que aconteceu dentro do período, e não o que foi criado nele e aconteceu depois. Assim, um mês fechado não muda mais. A data de referência de cada métrica está na tabela.
- **Fuso da clínica.** Dia e mês são calculados no fuso de `clinic_settings.time_zone`. O mês vai do dia 1, 00:00, até o dia 1 do mês seguinte, 00:00 (fim exclusivo), no fuso da clínica.
- **Só a clínica selecionada.** Toda consulta roda no banco da clínica, pelo acesso autorizado (ADR-014). Não existe agregado entre clínicas na Fase 1.
- **Vendas canceladas não contam** em nenhuma métrica, mesmo quando o cancelamento é posterior ao período. O relatório de um mês já passado pode, portanto, diminuir quando uma venda daquele mês é cancelada: é a correção do lançamento, não um novo acontecimento.
- **Correções de venda** valem pelo valor atual (procedimento, valor e data já corrigidos).
- **Sem custos.** Nada de gasto com anúncio, CPL, CAC, ROAS ou ROI (`AGENTS.md`, §1). Comparação entre meses é da Fase 2 (REL-04).

## Relatório mensal

| Métrica | Definição | Data de referência | Fonte |
|---|---|---|---|
| **Contatos novos** | Contatos criados no mês, inclusive os arquivados depois | `contacts.created_at` | `contacts` |
| **Leads por origem** | Leads criados no mês, agrupados pelo tipo de origem atual (inclusive "Não identificada") | `leads.opened_at` | `leads.origin_type` |
| **Leads por temperatura** | Leads criados no mês, agrupados pela temperatura atual, inclusive "sem temperatura" | `leads.opened_at` | `leads.temperature` |
| **Vendas fechadas** | Leads ganhos no mês com venda ativa | `leads.closed_at` | `leads`, `sales` |
| **Perdidos** | Leads perdidos no mês | `leads.closed_at` | `leads` |
| **Perdidos por motivo** | Perdidos agrupados pelo motivo, inclusive motivos arquivados depois | `leads.closed_at` | `leads.loss_reason_id` |
| **Taxa de conversão** | Vendas fechadas ÷ (vendas fechadas + perdidos); sem leads encerrados no mês, mostra "—" e nunca 0% | `leads.closed_at` | — |
| **Frios revertidos** | Vendas fechadas cujo lead esteve frio em algum momento | `leads.closed_at` | `lead_events` |
| **Faturamento registrado** | Soma de `amount_cents` das vendas ativas com data da venda no mês | `sales.sold_on` | `sales` |
| **Procedimentos mais vendidos** | Os 10 primeiros por quantidade de vendas ativas no mês, com o valor somado; o desempate é pelo maior valor e depois pelo nome | `sales.sold_on` | `sales`, `procedures` |

### Detalhes

- **Lead ganho com venda ativa.** O lead fica ganho mesmo quando a venda é cancelada (CRM-035). Por isso, "vendas fechadas" exige uma venda `ACTIVE` daquele lead. Um lead ganho cuja venda foi cancelada não conta como venda nem como perda.
- **"Esteve frio".** Existe um evento `TEMPERATURE_CHANGED` do lead com `to_value = 'COLD'`, em qualquer data. Isso inclui o lead aberto já como frio, porque a abertura com temperatura grava esse evento.
- **Duas datas para vendas.** "Vendas fechadas" e "Conversão" usam a data em que o lead foi ganho (`closed_at`). "Faturamento" e "Procedimentos" usam a data informada na venda (`sold_on`), que pode ser retroativa. Uma venda lançada em 01/12 com `sold_on` em 30/11 conta como fechada em dezembro e entra no faturamento de novembro. A tela mostra uma nota explicando isso.
- **Origem e temperatura atuais.** As duas são o valor atual do lead, já com as correções manuais (CRM-090), e não um retrato do fim do mês.
- **Arredondamento.** A conversão é exibida com uma casa decimal. O valor sai em centavos inteiros e é formatado só na tela.

### Exemplo numérico (novembro de 2026, fuso America/Sao_Paulo)

Dados do mês:
- 120 contatos criados.
- 100 leads criados:
  - origem: 45 Tráfego pago, 25 Instagram orgânico, 15 Indicação, 5 Google, 10 Não identificada;
  - temperatura atual: 30 frios, 25 mornos, 20 quentes, 10 qualificados, 15 sem temperatura.
- 25 leads ganhos com `closed_at` em novembro:
  - 20 com venda ativa, dos quais 6 estiveram frios em algum momento;
  - 5 com a venda cancelada depois.
- 30 leads perdidos em novembro: 12 Preço, 8 Sem resposta, 6 Escolheu outra clínica, 4 Desistiu.
- Vendas ativas com `sold_on` em novembro: 22, somando R$ 41.500,00, entre elas:
  - 2 lançadas em dezembro com data retroativa;
  - 9 Limpeza (R$ 2.700,00);
  - 7 Clareamento (R$ 14.000,00);
  - 6 Toxina (R$ 24.800,00).

Resultado:
- **Contatos novos:** 120.
- **Leads por origem:** 45 / 25 / 15 / 5 / 10.
- **Leads por temperatura:** 30 / 25 / 20 / 10 / 15.
- **Vendas fechadas:** 20. Os 5 leads com venda cancelada não contam.
- **Perdidos:** 30, com 12 / 8 / 6 / 4 por motivo.
- **Conversão:** 20 ÷ (20 + 30) = **40,0%**.
- **Frios revertidos:** 6.
- **Faturamento registrado:** R$ 41.500,00, ou seja, 4.150.000 centavos.
- **Procedimentos mais vendidos:** Limpeza (9), Clareamento (7), Toxina (6). A Limpeza vem primeiro porque o critério é a quantidade.

## Dashboard da recepção

Atualizado por consulta periódica na Fase 1; o tempo real é REL-03, da F2.

| Card | Definição |
|---|---|
| **Leads de hoje** | Leads criados hoje (`opened_at`), no fuso da clínica |
| **Vendas de hoje** | Quantidade e soma das vendas ativas com `sold_on` = hoje |
| **Vendas do mês** | Quantidade e soma das vendas ativas com `sold_on` no mês corrente até hoje |
| **Tarefas atrasadas** | Tarefas abertas com prazo antes de agora (mesma regra da CRM-040) |
| **Tarefas de hoje** | Tarefas abertas com prazo entre agora e a meia-noite da clínica |
| **Conversas em aberto** | Entra com a caixa de entrada (CRM-083) |
| **Aguardando atendimento** | Entra com a caixa de entrada (CRM-083) |

## Permissões

- O dashboard e o relatório são visíveis a todos os papéis da clínica, inclusive Somente leitura. São só números agregados, sem nomes nem telefones.
- Os números são os mesmos para todos os papéis. Não existe filtro "só meus leads" no MVP.
