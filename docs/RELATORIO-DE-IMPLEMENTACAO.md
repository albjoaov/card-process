# Relatório de Implementação (Cronológico)

Documento em português descrevendo, em ordem cronológica, os passos de implementação do **Card Process**, os erros encontrados ao longo do caminho e como cada um foi corrigido.

A implementação seguiu **Desenvolvimento Orientado a Especificação** (Spec-Driven Development) com o
[spec-kit](https://github.com/github/spec-kit): primeiro os artefatos de especificação, depois o
código, com verificação a cada etapa.

---

## Fase 0 — Preparação do ambiente

1. **Leitura da especificação** (`Projeto B.U Processamento ... .pdf`): ecossistema de 3
   microserviços (Produto, Portador, Cartão), fluxo assíncrono via SQS, cache Redis, JWT, PostgreSQL,
   Docker Compose com boot por um único comando, retry/DLQ, testes e documentação profissional.
2. **Levantamento de ferramentas**: presentes Java 17, Docker (engine **OrbStack**), `uv`, `gh`.
   Ausentes: Maven e o CLI do spec-kit.
3. **Ajuste de stack (correção do usuário)**: comecei a baixar o Maven via `curl`, mas o usuário
   interrompeu e instruiu usar **Java 21 (via SDKMAN, já instalado)** e **Spring Boot 3.x**.
   - Java 21 ainda não estava instalado no SDKMAN (só o 17). Instalei `21.0.11-amzn` e
     `Maven 3.9.16` via SDKMAN.
   - Observação importante de ambiente: Java/Maven só ficam no PATH após
     `source "$HOME/.sdkman/bin/sdkman-init.sh"` em cada shell.
4. **spec-kit**: validado com `uvx ... specify check` e inicializado com
   `specify init --here --integration claude --script sh`, criando `.specify/` (templates, scripts,
   constituição) e os skills `speckit-*`.
5. **Git** inicializado para a entrega.

---

## Fase 1 — Artefatos do spec-kit

6. `create-new-feature.sh` criou `specs/001-card-processing-ecosystem/` e persistiu o estado da
   feature em `.specify/feature.json`.
7. **Constituição** (`.specify/memory/constitution.md`): princípios inegociáveis — resiliência
   primeiro, integridade de dados acima de disponibilidade nas escritas, arquitetura limpa/SOLID,
   contratos explícitos, e testes dos fluxos críticos.
8. **`spec.md`**: 3 histórias de usuário priorizadas (P1 emissão, P2 catálogo, P3 resiliência),
   18 requisitos funcionais (FR-001..FR-018), critérios de sucesso mensuráveis, casos de borda.
9. **`plan.md`** + **`research.md`** (10 decisões técnicas com alternativas rejeitadas) +
   **`data-model.md`** + **`contracts/`** (OpenAPI por serviço + schema do `IssuanceMessage`) +
   **`quickstart.md`**.
10. **`tasks.md`**: 42 tarefas ordenadas por dependência, agrupadas por história.

---

## Fase 2 — Fundação

11. **`pom.xml` agregador**: Spring Boot 3.3.5, Java 21, gestão de dependências (spring-cloud-aws,
    springdoc, Resilience4j, jjwt, Testcontainers, WireMock).
12. **`shared-contracts`**: o record `IssuanceMessage` compartilhado entre produtor e consumidor.
13. **Infraestrutura**: `docker-compose.yml` (Postgres, Redis, LocalStack, 3 apps, health checks e
    `depends_on: service_healthy`), script de init do Postgres (banco por serviço) e script de init
    do LocalStack provisionando `card-issuance-queue` + `card-issuance-dlq` com redrive
    (`maxReceiveCount=5`).
14. **Dockerfiles multi-stage**: estágio de build com Maven (`-pl <svc> -am`) e runtime com JRE 21,
    para build a partir do código-fonte sem toolchain no host.

---

## Fase 3 — Serviços

### Produto Service (catálogo)
15. Entidade `Product` + `ProductStatus`, `BaseAuditEntity` com auditoria JPA
    (`createdAt`/`updatedAt`), `ProductService`, controller REST, handler global de exceções
    (RFC 7807), migração Flyway, springdoc.

> **Erro #1 — Reactor Maven não encontrava módulos.**
> O primeiro build do Produto falhou: `Child module .../portador-service/pom.xml does not exist`.
> O `pom.xml` agregador listava os 4 módulos, mas os `pom.xml` de `portador-service` e
> `cartao-service` ainda não existiam.
> **Correção:** criei antecipadamente os dois `pom.xml` (completos). O Maven só precisa que os POMs
> sejam carregáveis para montar o reactor; com `-pl produto-service -am`, apenas Produto +
> shared-contracts são construídos. Rebuild do Produto: OK (jar 53,6 MB).

### Portador Service (orquestrador)
16. Segurança **JWT** (`JwtService` HS256 com jjwt, `OncePerRequestFilter`, `SecurityConfig`
    stateless, BCrypt), `/auth/register` + `/auth/login`. Entidade `Cardholder` com **validador de
    CPF (dígitos verificadores)**, `CardholderService` que persiste e publica `IssuanceMessage` na
    SQS via `SqsTemplate` (CPF duplicado → 409). `CartaoClient` (RestClient com timeouts) +
    `AggregationService` (portador + cartão + produto). Build OK (jar 71,8 MB).

### Cartão Service (núcleo de emissão)
17. Consumidor `@SqsListener`, `CardIssuanceService` (idempotência + validação do produto **ATIVO na
    fonte da verdade**, não no cache), `ProductGateway` read-through, `RedisProductCache`,
    `ProdutoServiceClient` com **Resilience4j** (timeout, retry com backoff, circuit breaker, fallback).
    Build OK (jar 75 MB).

18. Compilação completa do reactor (main + testes): OK.

---

## Fase 4 — Testes e os erros mais relevantes

19. **Testes unitários: 14 verdes** (Produto 3, Portador 2, Cartão 9) — sem Docker.

> **Erro #2 — Testcontainers não encontrava o Docker (OrbStack).**
> Os testes de integração falhavam com `Could not find a valid Docker environment`. O socket
> respondia ao `curl ... /_ping` (OK), então não era conectividade.
> Capturando o log completo, a causa real apareceu:
> `client version 1.32 is too old. Minimum supported API version is 1.40` — o docker-java negocia a
> API **1.32**, e o **OrbStack exige ≥ 1.40**.
> Tentativas que **não** resolveram: setar `DOCKER_HOST`, criar `~/.testcontainers.properties`,
> exportar `DOCKER_API_VERSION=1.43` (inclusive com `forkCount=0`), e subir o Testcontainers para
> 1.20.4 — o docker-java continuava enviando 1.32.
> **Correção:** o docker-java lê a versão da API pela **system property `api.version`** (não pela env
> `DOCKER_API_VERSION`). Validei com `-Dapi.version=1.43` em processo (Produto IT passou) e fixei de
> forma portável no `pom.xml`: `argLine` do Surefire com `-Dapi.version=1.41` (1.41 satisfaz o piso
> 1.40 do OrbStack e é suportado por qualquer engine moderno), além de manter Testcontainers 1.20.4.
> Assim `mvn test` funciona sem ajustes de ambiente.

> **Erro #3 — Cache do Redis não fazia round-trip (produto buscado 3× em vez de 1×).**
> O teste de consumo do Cartão falhava: `Expected exactly 1 requests ... but received 3`. As duas
> leituras do cartão re-chamavam o Produto Service em vez de servir do cache.
> Primeira tentativa: troquei `GenericJackson2JsonRedisSerializer` por um
> `Jackson2JsonRedisSerializer<ProductSnapshot>` tipado — ainda 3.
> O log revelou a causa real:
> `Could not read JSON: Unrecognized field "active" (class ProductSnapshot)`. O método
> `ProductSnapshot.isActive()` era serializado pelo Jackson como um campo extra `"active"`; na
> leitura, a desserialização falhava, o cache era tratado como *miss* e cada leitura batia na origem
> (1 da emissão + 2 das leituras = 3).
> **Correção:** configurar o `ObjectMapper` do serializer com
> `FAIL_ON_UNKNOWN_PROPERTIES = false`. Reexecutando, o consumo passou (produto buscado exatamente
> 1×, leituras servidas do cache).

20. **Suíte completa: `mvn test` → BUILD SUCCESS, 20 testes verdes** (unitários + integração com
    Testcontainers reais: Postgres, Redis, LocalStack SQS, e WireMock para o Produto).

---

## Fase 5 — Subida do ambiente (Docker Compose) e correção final

21. `docker compose build` (em background): OK, 3 imagens (266–284 MB).

> **Erro #4 — LocalStack `unhealthy`; Portador e Cartão não subiram.**
> No `docker compose up -d`, apenas Postgres, Redis e Produto ficaram saudáveis; LocalStack ficou
> `unhealthy` e, por dependerem dele (`depends_on: service_healthy`), Portador e Cartão nem
> iniciaram. O `awslocal sqs list-queues` vinha vazio — a fila nunca era criada.
> Causa: o script `infra/localstack/init-sqs.sh` estava com permissão `-rw-r--r--` (não executável),
> e o LocalStack não roda os hooks de `ready.d` sem o bit de execução.
> **Correção:** `chmod +x infra/localstack/init-sqs.sh` e recriação do LocalStack. As filas
> (`card-issuance-queue` + `card-issuance-dlq`) passaram a ser criadas, o health check ficou verde e
> Portador/Cartão subiram. (O bit de execução é preservado pelo Git, então o boot do revisor
> também funciona.)

22. **Boot limpo do zero, validado:** `docker compose down -v` seguido de `docker compose up -d` →
    **6/6 contêineres saudáveis em ~15 s**.
23. **Fluxo ponta a ponta ao vivo:** criar produto → registrar/login (JWT) → registrar portador
    (dispara emissão) → consulta agregada retornou portador + **cartão ATIVO** + produto. Confirmado
    também: **401** sem token; **leitura de cartão continua 200 com o Redis parado** (fallback direto
    ao Produto Service).
24. Teardown final dos contêineres para liberar recursos (`docker compose up` os recria).

---

## Resultado

- **Código:** 80 arquivos Java, 4 módulos Maven + agregador, sem comentários, identificadores em inglês.
- **Testes:** 20 verdes (4 classes unitárias + 3 de integração com Testcontainers nos fluxos críticos
  de cadastro e consumo de fila).
- **Boot:** `docker compose up` sobe Postgres, Redis, LocalStack e os 3 serviços com um único comando.
- **Entregáveis:** `docker-compose.yml`, `README.md`, `postman_collection.json`, `IMPLEMENTATION.md`,
  artefatos do spec-kit em `specs/`, e as respostas das Partes 2 e 2.1 em `docs/`.

## Resumo dos erros e correções

| # | Sintoma | Causa raiz | Correção |
|---|---------|-----------|----------|
| 1 | Reactor falha: módulo não existe | POMs de Portador/Cartão ausentes | Criar os POMs antes do primeiro build |
| 2 | Testcontainers: "valid Docker environment" | docker-java envia API 1.32; OrbStack exige ≥1.40; `DOCKER_API_VERSION` ignorado | `-Dapi.version=1.41` no `argLine` do Surefire + Testcontainers 1.20.4 |
| 3 | Produto buscado 3× (cache não funcionava) | `isActive()` serializado como campo `"active"`, quebrando a leitura do cache | `FAIL_ON_UNKNOWN_PROPERTIES=false` no serializer do Redis |
| 4 | LocalStack unhealthy; serviços não sobem | `init-sqs.sh` sem permissão de execução; fila não criada | `chmod +x` no script de init |
