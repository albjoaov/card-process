---
description: "Task list for the Card Process"
---

# Tasks: Card Process

**Input**: Design documents from `specs/001-card-processing-ecosystem/`

**Tests**: Included for the critical paths (cardholder registration, queue consumption) per the spec.

**Organization**: Grouped by user story. US2 (catalog) is foundational reference data; US1 (issuance)
is the core MVP flow; US3 (resilience) hardens the flow.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: US1 (issuance), US2 (catalog), US3 (resilience), or INFRA/SETUP

## Phase 1: Setup (Shared Infrastructure)

- [ ] T001 [SETUP] Create Maven aggregator `pom.xml` with modules and dependency management (Spring Boot 3.3, Java 21).
- [ ] T002 [P] [SETUP] Create `shared-contracts` module: `IssuanceMessage`, status enums, `ApiError` shape.
- [ ] T003 [P] [SETUP] Add root `.gitignore`, `.dockerignore`.

## Phase 2: Foundational (Blocking Prerequisites)

- [ ] T004 [INFRA] Author `docker-compose.yml`: Postgres (multi-db init), Redis, LocalStack, 3 services, health checks, depends_on ordering.
- [ ] T005 [P] [INFRA] Postgres init script creating `produto_db`, `portador_db`, `cartao_db`.
- [ ] T006 [P] [INFRA] LocalStack init script provisioning `card-issuance-queue` + `card-issuance-dlq` with redrive policy (maxReceiveCount=5).
- [ ] T007 [P] [INFRA] Multi-stage Dockerfile per service (Maven build -> JRE 21 runtime).

## Phase 3: User Story 2 - Product Catalog (Priority: P2) - reference data first

**Goal**: CRUD-by-id product catalog with audit timestamps and status.

- [ ] T008 [US2] Produto module scaffold: pom, application class, `application.yml`, Flyway migration for `product`.
- [ ] T009 [US2] `Product` entity + `ProductStatus` enum + JPA auditing config (`BaseAuditEntity`).
- [ ] T010 [US2] `ProductRepository`.
- [ ] T011 [US2] `ProductService` (create, getById, cancel) + domain exceptions.
- [ ] T012 [US2] `ProductController` + DTOs; `GlobalExceptionHandler` (ProblemDetail); springdoc config.
- [ ] T013 [P] [US2] Unit tests for `ProductService`.
- [ ] T014 [P] [US2] Integration test (Testcontainers Postgres) for create + getById + 404.

**Checkpoint**: Produto Service runs and is independently testable.

## Phase 4: User Story 1 - Issue a card (Priority: P1) MVP

**Goal**: Authenticated registration -> SQS issuance -> consume -> validate product -> persist card ->
aggregated view.

### Portador Service

- [ ] T015 [US1] Portador module scaffold: pom, application class, `application.yml`, Flyway migrations (`app_user`, `cardholder`).
- [ ] T016 [US1] JWT security: `JwtService`, `JwtAuthenticationFilter`, `SecurityConfig`, `AppUser` + repository.
- [ ] T017 [US1] `AuthController` (register/login) + `PasswordEncoder` (BCrypt).
- [ ] T018 [US1] `Cardholder` entity + `CardholderStatus`; CPF validator; `CardholderRepository`.
- [ ] T019 [US1] `SqsIssuancePublisher` (infrastructure messaging) using `SqsTemplate`.
- [ ] T020 [US1] `CardholderService` + `IssuanceOrchestrator` (persist then publish IssuanceMessage; duplicate CPF -> 409).
- [ ] T021 [US1] `CartaoClient` (REST) to fetch issued card; `AggregationService` (cardholder + card + product).
- [ ] T022 [US1] `CardholderController` + `AuthController` DTOs; `GlobalExceptionHandler`; springdoc + security scheme.

### Cartao Service

- [ ] T023 [US1] Cartao module scaffold: pom, application class, `application.yml`, Flyway migration (`card`).
- [ ] T024 [US1] `Card` entity + `CardStatus`; `CardRepository` (unique correlation_id, cardholder_id).
- [ ] T025 [US1] `ProdutoServiceClient` (RestClient) + `ProductCache` (Redis) + `ProductGateway` (read-through).
- [ ] T026 [US1] `CardIssuanceService`: validate product ATIVO at source, idempotency guard, persist card.
- [ ] T027 [US1] `SqsIssuanceConsumer` (`@SqsListener`); throw on failure so SQS redrives to DLQ.
- [ ] T028 [US1] `CardController` (get by id, get by cardholder, update status) + DTOs; `GlobalExceptionHandler`; springdoc.

### Critical-path tests

- [ ] T029 [P] [US1] Unit tests: `CardholderService`/`IssuanceOrchestrator` publishes on create; duplicate CPF rejected.
- [ ] T030 [P] [US1] Unit tests: `CardIssuanceService` rejects missing/cancelled product; idempotent on redelivery.
- [ ] T031 [US1] Integration test (Testcontainers Postgres+SQS): register cardholder -> message enqueued.
- [ ] T032 [US1] Integration test (Testcontainers Postgres+Redis+SQS, WireMock Produto): consume -> card persisted; cache hit on second product read.

**Checkpoint**: Full happy-path issuance works end to end.

## Phase 5: User Story 3 - Resilience (Priority: P3)

**Goal**: Degrade gracefully under Redis/Produto failure; never `500`; never orphan cards.

- [ ] T033 [US3] Resilience4j on `ProdutoServiceClient`: timeouts, retry+backoff, circuit breaker.
- [ ] T034 [US3] `ProductGateway` fallback: on Redis failure call client directly; on read-path Produto failure surface 503.
- [ ] T035 [US3] Consumer write-path: Produto failure/unconfirmed -> throw -> redrive -> DLQ (assert no card).
- [ ] T036 [P] [US3] Integration test: Produto offline -> message lands in DLQ, zero cards created.
- [ ] T037 [P] [US3] Integration test: Redis offline -> product read still succeeds via direct call.

## Phase 6: Polish & Delivery

- [ ] T038 [P] Professional `README.md`: setup, Mermaid architecture, technical decisions, integrity guarantee explanation, resilience notes.
- [ ] T039 [P] `postman_collection.json` covering the full flow + resilience probes.
- [ ] T040 [P] `IMPLEMENTATION.md` describing the spec-kit-driven steps taken.
- [ ] T041 Build + run all tests green (`mvn test`); boot `docker compose up` and validate quickstart.
- [ ] T042 Answer Part 2 (leadership) and Part 2.1 (AI) questionnaires in `docs/`.

## Dependencies & Execution Order

- Setup (P1) -> Foundational (P2) -> US2 -> US1 -> US3 -> Polish.
- US2 first because US1 issuance validates products; the contract is needed by the Cartao consumer.
- Within a service: migrations/entities -> repositories -> services -> controllers -> tests.
- Resilience (US3) layers onto the working US1 client/consumer.

## Parallel Opportunities

- T002, T003 in parallel after T001.
- T005, T006, T007 in parallel within Foundational.
- Unit-test tasks marked [P] alongside their implementation once the class exists.
- README, Postman, IMPLEMENTATION docs (T038-T040) in parallel at the end.
