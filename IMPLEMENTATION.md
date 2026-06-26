# Implementation Walkthrough

This document describes, step by step, how the Card Process was built. The work
followed **Spec-Driven Development** using [spec-kit](https://github.com/github/spec-kit): every line
of code traces back to an approved specification, plan and task list.

## 0. Environment and tooling

- Confirmed the toolchain: **Java 21** (Amazon Corretto, via SDKMAN), **Maven 3.9**, **Docker**
  (OrbStack engine) and **Docker Compose**.
- Installed **spec-kit** with `uvx` and initialized it in the repository
  (`specify init --here --integration claude`), producing the `.specify/` templates, scripts and the
  constitution/spec/plan/tasks workflow.
- Initialized a Git repository for delivery.

## 1. Constitution (principles)

Wrote [`/.specify/memory/constitution.md`](.specify/memory/constitution.md) to lock the
non-negotiable principles before any design:

1. **Resilience first** — every cross-service/infra call assumes failure; async gets retry + DLQ.
2. **Data integrity over availability for writes** — never create a card for a missing/cancelled product.
3. **Clean architecture & SOLID** — `web -> application -> domain -> infrastructure`, dependencies inward.
4. **Explicit contracts** — OpenAPI per service, versioned wire DTOs, one error shape.
5. **Test the critical paths** — registration and queue consumption, with Testcontainers.

## 2. Specification

Wrote [`specs/001-card-processing-ecosystem/spec.md`](specs/001-card-processing-ecosystem/spec.md):
three prioritized, independently testable user stories (P1 issuance, P2 catalog, P3 resilience),
18 functional requirements (FR-001..FR-018), measurable success criteria, edge cases and assumptions.
Technology-agnostic by design — it describes *what*, not *how*.

## 3. Plan and design artifacts

Wrote [`plan.md`](specs/001-card-processing-ecosystem/plan.md) and supporting documents:

- [`research.md`](specs/001-card-processing-ecosystem/research.md) — ten technical decisions
  (D1..D10), each with rationale and rejected alternatives: SQS client, DLQ strategy, the
  non-existent-product guarantee, the Redis cache strategy, Resilience4j, idempotency, JWT,
  persistence/auditing/migrations, build/runtime topology, and the testing approach.
- [`data-model.md`](specs/001-card-processing-ecosystem/data-model.md) — entities, columns,
  constraints and state machines per service.
- [`contracts/`](specs/001-card-processing-ecosystem/contracts/) — OpenAPI for each service and the
  JSON schema for the `IssuanceMessage`.
- [`quickstart.md`](specs/001-card-processing-ecosystem/quickstart.md) — run/test/demo guide.

A **Constitution Check** gate in the plan confirmed the design honored every principle before coding.

## 4. Task breakdown

Wrote [`tasks.md`](specs/001-card-processing-ecosystem/tasks.md): 42 dependency-ordered tasks grouped
by user story (Setup -> Foundational -> US2 catalog -> US1 issuance -> US3 resilience -> Polish), so
each slice is independently buildable and testable.

## 5. Foundation

- **Aggregator `pom.xml`** — Spring Boot 3.3.5 parent, Java 21, dependency management for
  spring-cloud-aws (SQS), springdoc, Resilience4j, jjwt, Testcontainers and WireMock.
- **`shared-contracts`** module — the single `IssuanceMessage` record shared by producer and consumer.
- **Infrastructure** — `docker-compose.yml` (Postgres, Redis, LocalStack, the 3 services with health
  checks and `depends_on: service_healthy`), a Postgres init script (database-per-service) and a
  LocalStack init script that provisions `card-issuance-queue` + `card-issuance-dlq` with a redrive
  policy (`maxReceiveCount = 5`).
- **Multi-stage Dockerfiles** — a Maven build stage (reactor build with `-pl <svc> -am`) and a slim
  JRE 21 runtime, so no host toolchain is needed and the stack builds from source.

## 6. Produto Service (catalog, P2)

Layered implementation: `Product` entity + `ProductStatus`, a `BaseAuditEntity` mapped superclass
with JPA auditing (`createdAt`/`updatedAt`), `ProductService` (create / get / cancel), a REST
controller, a global exception handler returning RFC 7807 `ProblemDetail`, Flyway migration and
springdoc. Validated with unit tests and a Testcontainers (Postgres) API test.

## 7. Portador Service (orchestrator, P1)

- **JWT security** — `JwtService` (HS256, jjwt), a `OncePerRequestFilter`, stateless `SecurityConfig`,
  BCrypt password hashing, `/auth/register` + `/auth/login`.
- **Cardholder registration** — `Cardholder` entity with a custom **CPF check-digit validator**;
  `CardholderService` persists the cardholder and publishes an `IssuanceMessage` to SQS via
  `SqsTemplate` (duplicate CPF -> `409`).
- **Aggregated view** — `CartaoClient` (RestClient with timeouts) fetches the issued card;
  `AggregationService` assembles cardholder + card + product; a missing card degrades gracefully and a
  downstream outage surfaces as `503`.
- Critical-path tests: a unit test (publishes on register, rejects duplicate CPF) and a
  Testcontainers (Postgres + LocalStack SQS) test proving registration enqueues the message and that
  unauthenticated access is rejected.

## 8. Cartao Service (issuance core, P1 + P3)

- **SQS consumer** — `@SqsListener` deserializes `IssuanceMessage` and delegates to
  `CardIssuanceService`. On failure it throws, so the message is not acknowledged and SQS redrives it
  to the DLQ after 5 receives.
- **Integrity** — `CardIssuanceService` performs an idempotency check (unique `correlationId` /
  `cardholderId`), validates the product is `ATIVO` against the **Produto source of truth** (not the
  cache), and only then persists the card.
- **Redis cache** — `ProductGateway` is read-through: `RedisProductCache` (type-safe
  `Jackson2JsonRedisSerializer`) first, then origin on miss, warming the cache. The cache degrades to
  a miss when Redis is down.
- **Resilience4j** — `ProdutoServiceClient` is wrapped with timeouts, retry + exponential backoff and
  a circuit breaker; a fallback translates exhaustion/open-circuit into a single domain exception.
- Tests: unit tests for issuance (no card for missing/cancelled product, idempotent) and the gateway
  (cache hit/miss/degrade, require-active rules), plus a full Testcontainers consumption test
  (Postgres + Redis + SQS + WireMock) asserting the card is persisted, the product is fetched once and
  reads are cache hits, and that no card is created for a non-existent product.

## 9. Verification

- `mvn test` — **20 tests green** (unit + Testcontainers integration across all modules).
- `docker compose up --build` — the full environment builds and boots to a healthy state with one
  command; the end-to-end flow (register product -> authenticate -> register cardholder -> async
  issuance -> aggregated query) works, and the failure demos behave as designed.

## 10. Notable problems solved during build

- **Testcontainers vs. OrbStack** — docker-java negotiated API `1.32`, which OrbStack rejects (min
  `1.40`). Pinned the Docker API version via the surefire `argLine` (`api.version=1.41`) so `mvn test`
  works on modern engines without environment tweaks, and bumped Testcontainers to 1.20.4.
- **Redis cache round-trip** — the `ProductSnapshot.isActive()` accessor was serialized as an extra
  `"active"` field, breaking deserialization on read (every read missed the cache and re-hit the
  Produto Service). Fixed by making the cache serializer tolerant of unknown properties — proven by an
  integration test that asserts exactly one origin call across repeated reads.

## 11. Deliverables

- Source for the three services + `shared-contracts`.
- `docker-compose.yml` at the root (Postgres, Redis, LocalStack, 3 apps; single-command boot).
- `postman_collection.json` for manual testing.
- `README.md` (architecture, decisions, integrity guarantee, resilience, setup).
- The complete spec-kit artifact set under `specs/`.
- Answers to the leadership and AI questionnaires under `docs/`.
