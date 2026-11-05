# Próxima etapa — credenciais por clínica e prova de isolamento

Data: 05/11/2026. Status: plano técnico para implementação. Complementa ADR-014 e ADR-016; propostas abaixo ainda não estão implementadas.

## Objetivo da entrega

Provisionar duas clínicas de teste, com bancos e credenciais distintos, e demonstrar isolamento real no PostgreSQL antes de expor cadastro público. Não criar dados de produção nem endpoint administrativo público para essa prova.

## 1. Contratos de segredo

Extrair a leitura direta de Environment do TenantConnectionRepository para uma interface TenantSecretReader. O provisionador usa uma interface de escrita distinta. A API recebe somente capacidade de leitura; não recebe credenciais de provisionamento.

Proposta local: adaptador de arquivos em volume privado, leitura somente na API e escrita no worker. Referências e nomes derivados do UUID, sem aceitar caminhos do navegador. Não permitir symlinks ou travessia de diretórios; escrita atômica e verificação de identidade/versão. Arquivos fora do Git e das imagens. Definir armazenamento gerenciado da hospedagem antes de produção; o adaptador local não substitui criptografia e controles de acesso da infraestrutura.

Contrato mínimo: obter segredo por referência; provisionador cria versão sem sobrescrever outra identidade; rotação troca versão e invalida conexões antigas de forma coordenada. Nunca retornar senha ao frontend ou incluí-la em logs.

## 2. Papéis por tenant

Para UUID sem hífens <id>:

| Papel | Proposta de responsabilidade |
|---|---|
| owner_<id> | NOLOGIN, proprietário do banco e schema |
| migrator_<id> | LOGIN, pode assumir o papel proprietário para migrações |
| tenant_<id> | LOGIN, somente operações de dados autorizadas |

Manter database_name=clinic_<id> e o runtime já previsto no registro. Runtime sem privilégios globais, sem associação ao proprietário/migrador, sem TEMP/CREATE no banco, sem CREATE no schema e sem alteração da tenant_identity ou flyway_schema_history. Revogar privilégios de PUBLIC no banco/schema e acesso às funções não necessárias.

O provisionador deve conectar a banco de manutenção e usar nomes derivados de UUID. Não conceder acesso ao controle para viabilizar CREATE DATABASE. Validar o comportamento de associação/administração de papéis no PostgreSQL 17; se algo exigir superusuário, redesenhar o passo ou reservar bootstrap explícito, sem elevar o processo web.

## 3. Fluxo interno de provisionamento

- Service orquestra a operação e as transições de estado.
- Repositories concentram consultas ao controle e DDL de provisionamento. Identificadores não podem usar parâmetros JDBC comuns: validar UUID, derivar nomes e aplicar citação correta, sem interpolar entrada livre.
- Registrar etapa/tentativa e exclusão mútua antes de executar comandos externos à transação central.
- Criar proprietário, migrador e runtime; criar banco; aplicar db/tenant-migration; inserir identidade; configurar grants; armazenar segredo; verificar acesso.
- Somente ativar após todas as verificações.
- Retomar usando UUID/nomes existentes; conferir proprietário e identidade antes de reutilizar objetos. Não apagar banco com dados em compensação automática.

O worker terá uma conexão restrita ao controle para tarefas/estados e outra conta para provisionamento. A API não executará o provisionador nem receberá suas senhas. A credencial do controle do worker deve ter permissões próprias, evitando acesso a hashes de usuários sem necessidade.

## 4. Prova reproduzível de isolamento

Criar bancos A/B e registros temporários com nomes derivados de UUID, sem usar clientes reais. Toda limpeza deve verificar os identificadores exatos criados pelo teste e ocorrer em finally; nunca enumerar bancos por prefixo e apagá-los indiscriminadamente.

| Teste | Resultado exigido |
|---|---|
| Runtime A → banco A | Conecta e lê somente os dados autorizados |
| Runtime A → banco B | Conexão negada pelo PostgreSQL |
| Runtime B → banco A | Conexão negada pelo PostgreSQL |
| Runtime A/B → controle | Conexão negada |
| Runtime A → CREATE ROLE/DATABASE/TABLE | Negado |
| Runtime A → alterar identidade/histórico | Negado |
| Segredo ausente ou identidade incorreta | Conexão recusada/fechada, sem fallback |
| Clínica suspensa/participação revogada/conta inativa | Serviço recusa nova conexão |
| Reexecução de etapa após falha | Sem banco/papel/registro duplicado |
| Nova migração de tenant | Aplicada com migrador, nunca com runtime |

Conexões devem ser testadas com senha real por TCP; SET ROLE como administrador não substitui esse teste. Inspecionar também memberships entre papéis para impedir elevação indireta.

## 5. Pools e JPA

Depois da prova de isolamento: separar EntityManager central e tenant, configurar resolução de identidade validada e migrações por tenant. Definir limite global de conexões, criação sob demanda, expiração/fechamento de pools, rotação de segredos e ausência de fallback. Não criar um pool ilimitado para cada clínica. Sem entidades de negócio no EntityManager central.

## Ordem dos próximos itens

1. Contrato/adaptador local de segredos com testes de leitura, referência inválida e ausência. **Concluído em 05/11/2026** — [segredos-clinicas.md](segredos-clinicas.md).
2. Provisionamento interno de papéis e banco com tratamento de falha parcial.
3. Migração e ativação com verificação de identidade.
4. Prova A/B e limpeza segura.
5. Persistência JPA tenant e limites de conexões.
6. Retomar cadastro, verificação de e-mail, filas e interface, conforme plano-onboarding.md.

## Condição de conclusão

Só considerar a etapa de credenciais concluída quando a prova A/B passar e os segredos não estiverem na API como credenciais administrativas. A separação central já concluída, isoladamente, não comprova isolamento de clínicas.

## Progresso — 05/11/2026

Itens 1 a 4 concluídos e item 5 concluído quanto aos pools. A prova A/B passou com papéis sem superusuário, e a API não tem credenciais administrativas: a condição de conclusão desta etapa foi atendida. Detalhes em [provisionamento-clinicas.md](provisionamento-clinicas.md) e [segredos-clinicas.md](segredos-clinicas.md).

Diferenças em relação a este plano: não há `migrator_<id>` com login, porque as migrações são aplicadas pelo provisionador como dono. A persistência JPA de clínica fica para a primeira entidade de negócio (ADR-018). Próximo passo: item 6, retomar o cadastro conforme plano-onboarding.md.
