# Relato da Implementação

*[English](implementation-walkthrough.md)*

Este documento descreve, passo a passo, como o Card Process foi construído. O trabalho seguiu
**Desenvolvimento Orientado a Especificação** (Spec-Driven Development) usando o
[spec-kit](https://github.com/github/spec-kit): toda linha de código remonta a uma especificação,
plano e lista de tarefas aprovados.

## 0. Ambiente e ferramentas

- Toolchain confirmada: **Java 21** (Amazon Corretto, via SDKMAN), **Maven 3.9**, **Docker**
  (engine OrbStack) e **Docker Compose**.
- **spec-kit** instalado com `uvx` e inicializado no repositório
  (`specify init --here --integration claude`), gerando os templates, scripts e o fluxo de
  constituição/spec/plan/tasks em `.specify/`.
- Repositório Git inicializado para a entrega.

## 1. Constituição (princípios)

Escrito o [`/.specify/memory/constitution.md`](../../.specify/memory/constitution.md) para travar
os princípios inegociáveis antes de qualquer design:

1. **Resiliência primeiro** — toda chamada entre serviços/infra assume que pode falhar; o
   assíncrono tem retry + DLQ.
2. **Integridade de dados acima de disponibilidade nas escritas** — nunca criar um cartão para um
   produto ausente/cancelado.
3. **Arquitetura limpa e SOLID** — `web -> application -> domain -> infrastructure`, dependências
   apontando para dentro.
4. **Contratos explícitos** — OpenAPI por serviço, DTOs de contrato versionados, um único formato
   de erro.
5. **Testar os caminhos críticos** — cadastro e consumo de fila, com Testcontainers.

## 2. Especificação

Escrito o [`specs/001-card-processing-ecosystem/spec.md`](../../specs/001-card-processing-ecosystem/spec.md):
três histórias de usuário priorizadas e testáveis de forma independente (P1 emissão, P2 catálogo,
P3 resiliência), 18 requisitos funcionais (FR-001..FR-018), critérios de sucesso mensuráveis, casos
de borda e premissas. Agnóstico de tecnologia por design — descreve o *quê*, não o *como*.

## 3. Plano e artefatos de design

Escrito o [`plan.md`](../../specs/001-card-processing-ecosystem/plan.md) e os documentos de apoio:

- [`research.md`](../../specs/001-card-processing-ecosystem/research.md) — dez decisões técnicas
  (D1..D10), cada uma com justificativa e alternativas rejeitadas: cliente SQS, estratégia de DLQ,
  a garantia de produto inexistente, a estratégia de cache Redis, Resilience4j, idempotência, JWT,
  persistência/auditoria/migrações, topologia de build/runtime e a abordagem de testes.
- [`data-model.md`](../../specs/001-card-processing-ecosystem/data-model.md) — entidades, colunas,
  constraints e máquinas de estado por serviço.
- [`contracts/`](../../specs/001-card-processing-ecosystem/contracts/) — OpenAPI de cada serviço e
  o JSON schema do `IssuanceMessage`.
- [`quickstart.md`](../../specs/001-card-processing-ecosystem/quickstart.md) — guia de
  execução/teste/demo.

Um gate de **Constitution Check** no plano confirmou que o design honrava todos os princípios antes
de começar a codificar.

## 4. Divisão em tarefas

Escrito o [`tasks.md`](../../specs/001-card-processing-ecosystem/tasks.md): 42 tarefas ordenadas
por dependência, agrupadas por história de usuário (Setup -> Fundação -> US2 catálogo -> US1
emissão -> US3 resiliência -> Polimento), de forma que cada fatia seja buildável e testável de
forma independente.

## 5. Fundação

- **`pom.xml` agregador** — parent Spring Boot 3.3.5, Java 21, gestão de dependências para
  spring-cloud-aws (SQS), springdoc, Resilience4j, jjwt, Testcontainers e WireMock.
- **Módulo `shared-contracts`** — o único record `IssuanceMessage` compartilhado entre produtor e
  consumidor.
- **Infraestrutura** — `docker-compose.yml` (Postgres, Redis, LocalStack, os 3 serviços com health
  checks e `depends_on: service_healthy`), um script de init do Postgres (banco por serviço) e um
  script de init do LocalStack que provisiona `card-issuance-queue` + `card-issuance-dlq` com
  política de redrive (`maxReceiveCount = 5`).
- **Dockerfiles multi-stage** — um estágio de build Maven (reactor build com `-pl <svc> -am`) e um
  runtime JRE 21 enxuto, sem necessidade de toolchain no host, com build a partir do código-fonte.

## 6. Produto Service (catálogo, P2)

Implementação em camadas: entidade `Product` + `ProductStatus`, uma superclasse mapeada
`BaseAuditEntity` com auditoria JPA (`createdAt`/`updatedAt`), `ProductService`
(criar / buscar / cancelar), um controller REST, um handler global de exceções retornando
`ProblemDetail` RFC 7807, migração Flyway e springdoc. Validado com testes unitários e um teste de
API com Testcontainers (Postgres).

## 7. Portador Service (orquestrador, P1)

- **Segurança JWT** — `JwtService` (HS256, jjwt), um `OncePerRequestFilter`, `SecurityConfig`
  stateless, hashing de senha com BCrypt, `/auth/register` + `/auth/login`.
- **Cadastro de portador** — entidade `Cardholder` com um **validador de dígitos verificadores de
  CPF** customizado; `CardholderService` persiste o portador e publica um `IssuanceMessage` na SQS
  via `SqsTemplate` (CPF duplicado -> `409`).
- **Visão agregada** — `CartaoClient` (RestClient com timeouts) busca o cartão emitido;
  `AggregationService` monta portador + cartão + produto; um cartão ausente degrada graciosamente e
  uma indisponibilidade downstream aparece como `503`.
- Testes de caminho crítico: um teste unitário (publica no cadastro, rejeita CPF duplicado) e um
  teste com Testcontainers (Postgres + LocalStack SQS) provando que o cadastro enfileira a mensagem
  e que acesso não autenticado é rejeitado.

## 8. Cartao Service (núcleo de emissão, P1 + P3)

- **Consumidor SQS** — `@SqsListener` desserializa `IssuanceMessage` e delega para
  `CardIssuanceService`. Em caso de falha ele lança exceção, então a mensagem não é confirmada e a
  SQS a redireciona para a DLQ após 5 recebimentos.
- **Integridade** — `CardIssuanceService` faz uma checagem de idempotência (`correlationId` /
  `cardholderId` únicos), valida que o produto está `ATIVO` contra a **fonte da verdade do
  Produto** (não o cache), e só então persiste o cartão.
- **Cache Redis** — `ProductGateway` é read-through: primeiro `RedisProductCache`
  (`Jackson2JsonRedisSerializer` type-safe), depois a origem em caso de miss, aquecendo o cache. O
  cache degrada para miss quando o Redis está fora do ar.
- **Resilience4j** — `ProdutoServiceClient` é envolvido com timeouts, retry + backoff exponencial e
  um circuit breaker; um fallback traduz exaustão/circuito aberto numa única exceção de domínio.
- Testes: testes unitários para emissão (nenhum cartão para produto ausente/cancelado, idempotente)
  e para o gateway (cache hit/miss/degradação, regras de exigência de produto ativo), além de um
  teste de consumo completo com Testcontainers (Postgres + Redis + SQS + WireMock) verificando que
  o cartão é persistido, que o produto é buscado uma única vez e que as leituras batem no cache, e
  que nenhum cartão é criado para um produto inexistente.

## 9. Verificação

- `mvn test` — **20 testes verdes** (unitários + integração com Testcontainers em todos os
  módulos).
- `docker compose up --build` — o ambiente completo faz build e sobe saudável com um único
  comando; o fluxo ponta a ponta (registrar produto -> autenticar -> registrar portador -> emissão
  assíncrona -> consulta agregada) funciona, e as demos de falha se comportam como projetado.

## 10. Problemas relevantes resolvidos durante a construção

- **Reactor do Maven: módulos filhos ausentes** — o primeiro build do Produto falhou com
  `Child module .../portador-service/pom.xml does not exist`. O `pom.xml` agregador listava os
  quatro módulos, mas `portador-service` e `cartao-service` ainda não tinham `pom.xml` — o Maven
  precisa que todo POM listado seja carregável para montar o reactor, mesmo ao buildar um único
  módulo com `-pl produto-service -am`. Corrigido criando os dois POMs antecipadamente.
- **Testcontainers vs. OrbStack** — o docker-java negociava a API `1.32`, que o OrbStack rejeita
  (mínimo `1.40`). Configurar `DOCKER_HOST`, um arquivo `~/.testcontainers.properties` e a variável
  de ambiente `DOCKER_API_VERSION` não mudaram a versão negociada — o docker-java lê a versão pela
  **system property `api.version`**, não por essa env var. Corrigido fixando a versão da API
  Docker via `argLine` do Surefire (`api.version=1.41`), fazendo o `mvn test` funcionar em engines
  modernas sem ajustes de ambiente, além de atualizar o Testcontainers para 1.20.4.
- **Round-trip do cache Redis** — o acessor `ProductSnapshot.isActive()` era serializado como um
  campo extra `"active"`, quebrando a desserialização na leitura (toda leitura errava o cache e
  batia de novo no Produto Service). Corrigido tornando o serializer do cache tolerante a
  propriedades desconhecidas — comprovado por um teste de integração que verifica exatamente uma
  chamada à origem em leituras repetidas.
- **LocalStack preso em `unhealthy`** — no `docker compose up -d`, o LocalStack nunca ficava
  saudável, então Portador e Cartao (que dependem dele via `depends_on: service_healthy`) nunca
  subiam, e `awslocal sqs list-queues` retornava vazio. O script de provisionamento da fila,
  `infra/localstack/init-sqs.sh`, não era executável (`-rw-r--r--`), e o LocalStack ignora
  silenciosamente hooks não executáveis em `ready.d`. Corrigido com
  `chmod +x infra/localstack/init-sqs.sh` — o Git preserva o bit de execução, então um clone novo
  sobe limpo.

| # | Sintoma | Causa raiz | Correção |
|---|---------|-----------|----------|
| 1 | Reactor falha: módulo não existe | POMs de Portador/Cartao ainda não existiam | Criar os dois POMs antes do primeiro build |
| 2 | Testcontainers: "valid Docker environment" | docker-java envia API `1.32`; OrbStack exige ≥1.40; `DOCKER_API_VERSION` é ignorada | `-Dapi.version=1.41` no `argLine` do Surefire + Testcontainers 1.20.4 |
| 3 | Produto buscado 3× em vez de 1× (cache não funcionava) | `isActive()` serializado como campo extra `"active"`, quebrando a leitura do cache | `FAIL_ON_UNKNOWN_PROPERTIES=false` no serializer do Redis |
| 4 | LocalStack unhealthy; serviços não sobem | `init-sqs.sh` sem permissão de execução; fila nunca criada | `chmod +x` no script de init |

## 11. Hardening pós-revisão: outbox transacional

Uma revisão do fluxo de cadastro encontrou uma lacuna clássica de **dual-write**: o insert do
portador (PostgreSQL) e a publicação do `IssuanceMessage` (SQS) eram duas escritas independentes
dentro de um único método `@Transactional` — uma publicação bem-sucedida antes de um commit que
falhasse podia deixar uma mensagem em trânsito para um portador que nunca chegou a existir.
Corrigido com um **outbox transacional**:

- `OutboxIssuancePublisher` agora implementa a porta `IssuancePublisher`: serializa a mensagem e
  insere uma linha em `outbox_message` **na mesma transação** do portador (ele se recusa a rodar
  sem uma transação ativa). `CardholderService` não mudou.
- `OutboxRelay` (`@Scheduled`) drena as linhas `PENDING` em lotes com `FOR UPDATE SKIP LOCKED` —
  seguro para múltiplas instâncias concorrentes —, publica na SQS e marca como `PUBLISHED`; falhas
  registram o erro e reagendam com backoff exponencial limitado. A entrega é **at-least-once**; a
  idempotência do consumidor do Cartao absorve duplicatas.
- O transporte SQS (`SqsIssuanceSender`) fala diretamente com o `SqsAsyncClient` usando um cache de
  URL de fila que só armazena sucessos, depois de descobrir que o `SqsTemplate` armazena uma
  resolução de fila **falha** para sempre (`computeIfAbsent` sobre um mapa de
  `CompletableFuture`) — com isso, o relay jamais se recuperaria de uma indisponibilidade da SQS
  sem um restart. Também foi definido `queue-not-found-strategy: FAIL` para que o produtor nunca
  provisione infraestrutura silenciosamente.
- Comprovado pelo `OutboxDeliveryIntegrationTest`: o cadastro durante uma indisponibilidade da SQS
  continua retornando `201` e mantém a linha `PENDING` com tentativas crescentes; assim que a fila
  aparece, o relay entrega e o payload corresponde ao portador cadastrado.

## 12. Entregáveis

- Código-fonte dos três serviços + `shared-contracts`.
- `docker-compose.yml` na raiz (Postgres, Redis, LocalStack, 3 apps; boot com um único comando).
- `postman_collection.json` para testes manuais.
- `README.md` (arquitetura, decisões, garantia de integridade, resiliência, setup).
- O conjunto completo de artefatos do spec-kit em `specs/`.
