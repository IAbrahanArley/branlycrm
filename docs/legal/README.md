# Páginas legais (rascunho)

> **Rascunho de 12/11/2026, pendente de revisão jurídica.** Escrito para destravar a revisão do app da Meta (Tech Provider, ADR-034). Não declara conformidade com a LGPD: a revisão de privacidade é a CRM-073.

## Onde estão

| Página | Rota pública | Texto |
|---|---|---|
| Política de privacidade | `/privacidade` | `frontend/src/components/legal/privacy.ts` |
| Termos de uso | `/termos` | `frontend/src/components/legal/terms.ts` |
| Exclusão de dados (exigida pela Meta) | `/exclusao-de-dados` | `frontend/src/components/legal/deletion.ts` |

Os dados da empresa ficam num único arquivo, `frontend/src/components/legal/legal-entity.ts`. As páginas usam `LegalPage`, e o rodapé da landing aponta para as três.

## Antes de publicar ou de informar à Meta

Substituir em `legal-entity.ts` todos os valores entre colchetes:

| Campo | O que é |
|---|---|
| `cnpj` | CNPJ da empresa (a verificação da Meta está em nome de AbeSolutiom) |
| `address` | Endereço da sede |
| `privacyEmail` | E-mail para pedidos de titulares e do encarregado |
| `supportEmail` | E-mail de suporte |
| `dataOfficer` | Nome do encarregado pelo tratamento de dados (DPO) |
| `forum` | Comarca do foro dos termos |
| `retentionDays` | Prazo de exclusão depois do fim do contrato (decisão pendente, CRM-073) |
| `updatedAt` | Data da versão publicada |

## Premissas que a revisão jurídica deve confirmar

- **Papéis:**
  - a clínica é a **controladora** dos dados dos pacientes e contatos;
  - a empresa é **operadora** desses dados e controladora dos dados dos usuários do sistema.
- **Suboperadores e transferência internacional:** AWS (São Paulo), Vercel, Resend e Meta. Vercel e Resend podem tratar dados fora do Brasil.
- **Custos do WhatsApp:** pagos pela clínica à Meta, salvo acordo escrito. Isso depende da decisão de cobrança pendente.
- **Prazos:** 15 dias para responder aos titulares; registros de acesso pelo prazo do Marco Civil.
- **Cookies:** só os essenciais (sessão e CSRF); a landing não usa rastreamento.
- **Mensagens:** mensagens automáticas e campanhas não fazem parte do serviço atual. Se entrarem, os textos precisam mudar.

## Para o painel da Meta

Depois de publicar com os valores preenchidos, informar em Configurações do app → Básico:
- a URL da política de privacidade: `https://<domínio>/privacidade`;
- a URL dos termos de serviço: `https://<domínio>/termos`;
- a URL de instruções de exclusão de dados: `https://<domínio>/exclusao-de-dados`.
