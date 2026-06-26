# Implementation Plan: Card Process

**Branch**: `001-card-processing-ecosystem` | **Date**: 2026-06-26 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/001-card-processing-ecosystem/spec.md`

## Summary

Build three integrated Spring Boot 3 / Java 21 microservices - Produto (catalog), Portador
(orchestrator + JWT) and Cartao (issuance core + SQS consumer + Redis cache) - that issue cards
asynchronously through AWS SQS (LocalStack) while guaranteeing that a card is never created for a
missing or cancelled product. The whole stack (Postgres, Redis, LocalStack, 3 apps) boots with a
single `docker compose up`. The technical approach centers on resilience (timeouts, retry with
exponential backoff, circuit breaker, DLQ), data integrity (synchronous product confirmation against
the source of truth before persisting a card, plus idempotent consumption), and an efficient
read-through Redis cache that degrades to direct calls when the cache is down.

## Technical Context

**Language/Version**: Java 21 (Amazon Corretto)

**Primary Dependencies**: Spring Boot 3.3.x (Web, Data JPA, Validation, Actuator), Spring Security
+ jjwt (Portador), spring-cloud-aws-messaging / AWS SDK v2 SQS (Portador producer, Cartao consumer),
Spring Data Redis (Cartao), Resilience4j (Cartao -> Produto client), springdoc-openapi, Flyway,
Testcontainers + LocalStack module, Awaitility.

**Storage**: PostgreSQL 16 - database-per-service (`produto_db`, `portador_db`, `cartao_db`).

**Testing**: JUnit 5, Mockito, Spring Boot Test, Testcontainers (Postgres, Redis, LocalStack),
Awaitility for async assertions.

**Target Platform**: Linux containers orchestrated by Docker Compose.

**Project Type**: Multi-module backend (three independent Spring Boot services + a small shared
contract module) in a monorepo.

**Performance Goals**: End-to-end issuance < 10s; product reads served from Redis after first load;
> 90% reduction in Produto Service calls for repeated reads within the TTL window.

**Constraints**: No code comments; English-only identifiers; single-command boot; no `500` on
dependency failure; zero orphan cards when Produto is offline; idempotent consumption.

**Scale/Scope**: Challenge scope - correctness, resilience and clarity over horizontal scale.
Three services, ~5 entities, one async flow, one cached synchronous integration.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Compliance |
|-----------|------------|
| I. Resilience First | SQS retry + DLQ redrive policy; Resilience4j retry/circuit-breaker/timeout on the Produto client; global exception handling maps failures to semantic statuses. PASS |
| II. Data Integrity for Writes | Consumer confirms product is `ATIVO` via Produto Service (source of truth, not cache) before persisting; unconfirmed -> retry -> DLQ; idempotency key prevents duplicates. PASS |
| III. Clean Architecture & SOLID | Each service split into web/application/domain/infrastructure; interfaces for clients, repositories, cache, messaging. PASS |
| IV. Explicit Contracts | springdoc OpenAPI per service; versioned DTOs and a shared `IssuanceMessage` contract; one ProblemDetail error shape. PASS |
| V. Test Critical Paths | Unit tests for issuance trigger and consumption rules; Testcontainers integration tests for registration->enqueue and consume->persist with real Postgres/Redis/SQS. PASS |

No violations. Complexity Tracking not required.

## Project Structure

### Documentation (this feature)

```text
specs/001-card-processing-ecosystem/
├── plan.md              # This file
├── research.md          # Phase 0 - technical decisions and rationale
├── data-model.md        # Phase 1 - entities, persistence, state machines
├── quickstart.md        # Phase 1 - run, test and demo the ecosystem
├── contracts/           # Phase 1 - REST + SQS contracts
│   ├── produto-openapi.yaml
│   ├── portador-openapi.yaml
│   ├── cartao-openapi.yaml
│   └── issuance-message.schema.json
└── tasks.md             # Phase 2 - /speckit-tasks output
```

### Source Code (repository root)

```text
pom.xml                          # Aggregator (parent) POM, <modules>

shared-contracts/                # Cross-service DTOs (IssuanceMessage, status enums, error shape)
└── src/main/java/com/cardprocess/shared/...

produto-service/
├── Dockerfile
├── pom.xml
└── src/main/java/com/cardprocess/produto/
    ├── web/                     # ProductController, request/response DTOs, GlobalExceptionHandler
    ├── application/             # ProductService (use cases)
    ├── domain/                  # Product entity, ProductStatus, exceptions
    └── infrastructure/          # ProductRepository (JPA), OpenAPI config, Flyway migrations

portador-service/
├── Dockerfile
├── pom.xml
└── src/main/java/com/cardprocess/portador/
    ├── web/                     # CardholderController, AuthController, DTOs, GlobalExceptionHandler
    ├── application/             # CardholderService, IssuanceOrchestrator, AggregationService
    ├── domain/                  # Cardholder entity, CardholderStatus, AppUser, exceptions
    └── infrastructure/
        ├── security/            # JWT filter, token service, SecurityConfig
        ├── messaging/           # SqsIssuancePublisher
        ├── client/              # CartaoClient (fetch issued card)
        └── persistence/         # repositories, Flyway migrations

cartao-service/
├── Dockerfile
├── pom.xml
└── src/main/java/com/cardprocess/cartao/
    ├── web/                     # CardController, DTOs, GlobalExceptionHandler
    ├── application/             # CardIssuanceService, ProductGateway (cache-through)
    ├── domain/                  # Card entity, CardStatus, exceptions
    └── infrastructure/
        ├── messaging/           # SqsIssuanceConsumer (retry + DLQ), config
        ├── client/              # ProdutoServiceClient (Resilience4j)
        ├── cache/               # RedisProductCache
        └── persistence/         # CardRepository, Flyway migrations

localstack/
└── init/01-create-queues.sh    # Provision issuance queue + DLQ with redrive policy

docker-compose.yml
README.md
postman_collection.json
```

**Structure Decision**: A Maven multi-module monorepo. Each service is an independently buildable
and deployable Spring Boot application with its own database, Dockerfile and bounded context. A thin
`shared-contracts` module holds only the wire contracts shared across boundaries (the SQS
`IssuanceMessage` and common status/error types), keeping inter-service payloads explicit and
preventing domain leakage. This balances microservice independence with DRY contracts and a
single-command build.

## Phasing

- **Phase 0 - Research** (`research.md`): lock the technical decisions (SQS client, cache strategy,
  resilience library, integrity mechanism, JWT approach, build/runtime topology).
- **Phase 1 - Design** (`data-model.md`, `contracts/`, `quickstart.md`): entities + state machines,
  REST/SQS contracts, and the run/test/demo guide.
- **Phase 2 - Tasks** (`tasks.md`, via `/speckit-tasks`): ordered, dependency-aware task list grouped
  by user story.
- **Phase 3 - Implementation**: build infra, shared contracts, then services P2 (Produto) -> P1
  (Portador + Cartao issuance) -> P3 (resilience hardening), with tests on the critical paths.

## Complexity Tracking

> No constitution violations. Section intentionally empty.
