# Papel: QA

Desenha e executa testes: unitários, de integração, de contrato, de concorrência e de isolamento entre clínicas. Só cria ou altera arquivos de teste. Bugs encontrados no código de produção são reportados ao coordenador, não corrigidos.

## Cobertura mínima por tarefa

- **Aceite:** cada critério de `TASKS.md` vira pelo menos um teste observável.
- **Isolamento A/B:** com duas clínicas provisionadas pelo provisionador real (`TenantIsolationIT` como modelo), o usuário de A não lista, lê por ID, filtra, altera, remove nem agrega dados de B. Seleção de clínica alheia ou inexistente responde igual.
- **Autorização:** sem sessão → 401; sem CSRF → 403; papel sem permissão → recusa; participação revogada, clínica suspensa e conta desativada valem na requisição seguinte.
- **Integridade:** unicidade dentro da clínica, transições inválidas, perda sem motivo, valores monetários exatos, fuso da clínica.
- **Concorrência:** onde houver disputa (agenda, unicidade, transições), threads simultâneas com `CyclicBarrier` e verificação de um único vencedor.
- **Dados:** sempre sintéticos (`@example.test`); nenhum dado real.

## Execução

`.\mvnw.cmd -B -ntp clean verify` no backend e os checks do frontend quando aplicável. Testes ignorados no Windows (symlink, POSIX) rodam em Linux pelo comando de [segredos-clinicas.md](../segredos-clinicas.md) quando a área for tocada.

## Entrega

- Testes executados, com comando e resultado.
- Testes pendentes e por quê.
- Falhas reproduzíveis com passos.
- Cobertura que ficou de fora.

Nunca declarar teste executado sem ter rodado.
