# Ambiente Docker local

Data: 05/11/2026.

## Arquitetura

Navegador → Nginx → Next.js ou Spring MVC → PostgreSQL 17 com pgvector.

Nginx atua como proxy; Next.js continua executando em Node.js com saída standalone. Não é uma exportação estática. URL padrão: http://localhost:8088. Requisições do navegador à API usam /api/; requisições do servidor Next podem usar API_INTERNAL_URL=http://backend:8080/api.

Banco, backend e frontend não publicam portas no host. Apenas Nginx publica uma porta vinculada a 127.0.0.1. Os nomes postgres, backend e frontend são resolvidos pela rede do Compose.

## Uso

Na raiz do repositório, copie .env.example para .env caso este ainda não exista e defina uma senha. A configuração inicial criou uma senha local aleatória. Nunca versionar .env.

```sh
docker compose up --build -d
docker compose ps
docker compose logs --tail=100 backend frontend nginx
docker compose down
```

O volume postgres_data preserva os dados após down. Não usar down -v se deseja preservar os dados. Alterar POSTGRES_PASSWORD no .env não altera a senha de um banco já inicializado.

## Verificação

- Página: http://localhost:8088/
- Saúde API/banco: http://localhost:8088/api/actuator/health
- Demais endpoints continuam protegidos pelo Spring Security. A autenticação provisória não substitui o módulo de login do escopo.
- Swagger, quando autenticado: /api/swagger-ui/index.html; contrato: /api/v3/api-docs.

## Inicialização

PostgreSQL saudável → backend saudável → frontend saudável → Nginx. Flyway habilita a extensão vector na primeira migração. Hibernate valida estrutura e não cria tabelas automaticamente. Banco de domínio ainda não modelado.

Backend: build Maven/Java 21 e execução JRE 21 com usuário sem privilégios. Frontend: npm ci, build Next e execução standalone com usuário node. Os builds não executam testes; isso deve ser feito nas verificações do projeto.

O perfil docker desativa a integração automática Spring Docker Compose. O antigo backend/compose.yaml não cria um segundo banco. O Compose da raiz é a fonte única deste ambiente.

## Limites

Ambiente local HTTP. TLS, domínio, segredos de produção, backups agendados e monitoramento externo serão definidos para hospedagem. IA, WhatsApp, armazenamento e cobrança ainda não estão integrados.

## Resultado da verificação

Em 05/11/2026: imagens de backend e frontend compiladas; Compose iniciado; página e saúde retornando HTTP 200 pelo Nginx; chamada interna do frontend à API retornando 200/UP; migração V1 e extensão vector confirmadas. Login de produto e módulos funcionais permanecem pendentes.


## Atualização de autenticação

HTTP Basic foi substituído por login por sessão em /api/v1/auth/login. Apenas saúde, obtenção de CSRF e processamento de login são públicos; o login exige token CSRF. Não existem credenciais padrão. Ver autenticacao.md.
