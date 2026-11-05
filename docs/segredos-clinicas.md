# Segredos das clínicas

Implementado em 05/11/2026. Item 1 de [plano-isolamento-clinicas.md](plano-isolamento-clinicas.md); complementa ADR-014 e ADR-016.

## Contrato

| Capacidade | Classe | Quem recebe |
|---|---|---|
| Leitura | `tenant/secrets/TenantSecretReader` (`FileTenantSecretReader`) | API, como bean Spring |
| Escrita | `tenant/secrets/FileTenantSecretWriter` | Somente o provisionador futuro; não é bean e o ArchUnit impede seu uso fora do pacote `secrets` |

`TenantConnectionRepository` lê o segredo pelo `TenantDatabase` autorizado, nunca por caminho ou referência vinda do navegador. Sem diretório configurado, toda conexão de clínica falha com `TenantSecretException` (`MISSING`); não há fallback para variáveis de ambiente nem para o banco central.

## Formato local

Um arquivo regular por clínica, com o nome da referência do registro (`TENANT_SECRET_<UUID sem hífens em maiúsculas>`), no diretório `app.tenancy.secrets.directory`:

```text
tenant=<uuid da clínica>
version=<inteiro positivo>
password=<16 a 256 caracteres ASCII visíveis>
```

- **Leitura:** recusa symlinks, diretórios e arquivos acima de 1 KiB. Recusa também formato inválido ou `tenant` de outra clínica (`INVALID`). Arquivo ausente resulta em vazio. Mensagens e `toString` nunca contêm a senha.
- **Criação:** grava num arquivo temporário (0600 em POSIX), sincroniza e publica por hard link. Se a clínica já tiver segredo, retorna `ALREADY_EXISTS`. Uma retomada de provisionamento reutiliza o segredo existente em vez de gerar outro.
- **Rotação:** exige a versão esperada (`VERSION_CONFLICT` caso contrário). Grava a versão seguinte com movimentação atômica, sob bloqueio de arquivo por clínica. Hoje cada operação abre uma conexão nova, então a senha nova vale na próxima operação. Quando houver pools, a versão servirá para descartar conexões antigas.

## Docker

O volume `tenant_secrets` é montado em `/run/branly/tenant-secrets` como somente leitura no backend (`APP_TENANCY_SECRETS_DIRECTORY`). Ele começa vazio. Nenhuma clínica tem credencial até o provisionador existir. O diretório configurado precisa existir na inicialização, senão a API não sobe.

## Limites

O adaptador local não substitui criptografia e controle de acesso de um gerenciador de segredos. Antes de produção, escolher o armazenamento da hospedagem e o procedimento de rotação auditado. Backup do volume, retenção e revogação de versões antigas também ficam para a hospedagem.

## Verificação

`FileTenantSecretStoreTest` cobre:
- leitura;
- ausência;
- não sobrescrita, inclusive com 8 criações concorrentes;
- rotação com conflito de versão;
- segredo de outra clínica;
- 11 formatos inválidos e arquivo grande;
- symlink e diretório;
- permissões POSIX;
- valores inválidos.

No Windows, os casos de symlink e POSIX são ignorados. Eles passaram em Linux, num container `maven:3.9-eclipse-temurin-21`, como usuário sem root. O comando abaixo roda em Git Bash, em `backend/`:

```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W):/src:ro" maven:3.9-eclipse-temurin-21 sh -c 'cp -r /src /work && cd /work && rm -rf target && useradd -m tester && chown -R tester /work && su tester -c "mvn -q -B -ntp -Dspotless.check.skip=true -Dtest=FileTenantSecretStoreTest test"; grep -h "Tests run" target/surefire-reports/*.txt'
```

`TenantConnectionIT` lê senhas publicadas pelo gravador real e cobre ainda:
- segredo ausente;
- senha do arquivo diferente da do papel, o que gera recusa do PostgreSQL.
