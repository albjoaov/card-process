# Feature Specification: Card Process

**Feature Branch**: `001-card-processing-ecosystem`

**Created**: 2026-06-26

**Status**: Approved

**Input**: User description: "Card Process card processing ecosystem: Produto, Portador and Cartao microservices with SQS, Redis, JWT and Postgres"

## Context

Card Process is expanding its card-processing infrastructure. The ecosystem manages the lifecycle of
**Products**, **Cards**, and **Cardholders** across three integrated microservices, handling
asynchronous flows and guaranteeing data availability through caching. The platform belongs to
the financial sector: it must anticipate network failures and data inconsistency, and never leave
a cardholder without a response.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Issue a card for a new cardholder (Priority: P1)

An authenticated operator registers a cardholder. The system persists the cardholder and
asynchronously triggers card issuance. The Cartao Service consumes the issuance request,
confirms the referenced product exists and is active, enriches the card with cached product
details, and persists an active card. The operator can then query an aggregated view returning
cardholder + card + product in a single response.

**Why this priority**: This is the core business value of the ecosystem - the end-to-end issuance
flow that ties all three services together through both synchronous and asynchronous integration.

**Independent Test**: Authenticate, create a product, register a cardholder referencing that
product, then poll the aggregated endpoint until it returns the cardholder with an active card and
the product details. Verifiable with the running compose environment and the API collection.

**Acceptance Scenarios**:

1. **Given** a valid JWT and an active product, **When** a cardholder is registered referencing that
   product, **Then** the cardholder is persisted with status `ATIVO` and an issuance message is
   enqueued to SQS.
2. **Given** an issuance message for an active product, **When** the Cartao Service consumes it,
   **Then** a card is created with status `ATIVO`, linked to the cardholder and product.
3. **Given** an issued card, **When** the aggregated endpoint is queried for the cardholder,
   **Then** the response contains the cardholder, the card, and the product details.
4. **Given** no JWT or an invalid JWT, **When** any protected Portador endpoint is called,
   **Then** the response is `401 Unauthorized`.

### User Story 2 - Manage the product catalog (Priority: P2)

A catalog manager registers card products (e.g. Black, Gold, Platinum) and retrieves them by id.
Every product carries audit timestamps (created/updated) and a status of `ATIVO` or `CANCELADO`.

**Why this priority**: Products are the reference data the issuance flow depends on. Without a
catalog there is nothing to issue, but the catalog itself is simple CRUD and independently testable.

**Independent Test**: Create a product via REST, fetch it by id, confirm persisted fields and audit
timestamps; cancel it and confirm status transition.

**Acceptance Scenarios**:

1. **Given** valid product data, **When** a product is created, **Then** it is persisted with status
   `ATIVO` and populated `createdAt`/`updatedAt` timestamps, returning `201 Created`.
2. **Given** an existing product id, **When** it is fetched, **Then** the product is returned with
   `200 OK`.
3. **Given** an unknown product id, **When** it is fetched, **Then** the response is `404 Not Found`
   with a problem-detail body.

### User Story 3 - Stay resilient when dependencies degrade (Priority: P3)

The ecosystem keeps responding when infrastructure misbehaves: if Redis is down, the Cartao Service
falls back to calling the Produto Service directly; if the Produto Service is unstable, calls are
retried with backoff and a circuit breaker, and issuance messages that cannot be confirmed are
retried and finally routed to a DLQ instead of creating an orphan card.

**Why this priority**: Resilience is an evaluation criterion and a production necessity, but it is
layered on top of the working happy path delivered by US1.

**Independent Test**: Stop Redis and confirm reads still succeed via direct product calls; stop the
Produto Service and confirm issuance messages are retried and land in the DLQ rather than producing
cards; confirm the aggregated endpoint degrades gracefully instead of returning `500`.

**Acceptance Scenarios**:

1. **Given** Redis is unavailable, **When** a card is created or read, **Then** product details are
   fetched directly from the Produto Service and the operation still succeeds.
2. **Given** the Produto Service is offline, **When** an issuance message references a product,
   **Then** the consumer retries with backoff and, after exhausting retries, the message is moved to
   the DLQ and no card is created.
3. **Given** a message referencing a non-existent or cancelled product, **When** it is consumed,
   **Then** no card is created and the message is dead-lettered.

### Edge Cases

- Duplicate cardholder CPF -> rejected with `409 Conflict`.
- Issuance message redelivered after a card was already created -> consumer is idempotent and does
  not create a duplicate card.
- Aggregated query when the card has not been issued yet -> returns the cardholder with a clearly
  absent card rather than failing.
- Product cancelled after a card was issued -> existing card is unaffected; new issuance for that
  product is rejected.
- Invalid CPF format or future birth date -> rejected with `400 Bad Request` and field-level details.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Produto Service MUST allow creating a product (name, status) and fetching it by id via REST.
- **FR-002**: Produto Service MUST support product status `ATIVO` and `CANCELADO`.
- **FR-003**: Produto Service MUST persist audit timestamps (`createdAt`, `updatedAt`) on every product.
- **FR-004**: Portador Service MUST authenticate callers via JWT and reject unauthenticated access to protected endpoints.
- **FR-005**: Portador Service MUST register a cardholder with name, CPF, birth date, and status (`ATIVO`, `BLOQUEADO`, `CANCELADO`).
- **FR-006**: On cardholder registration, Portador Service MUST enqueue an issuance message to AWS SQS with the data needed to create a card.
- **FR-007**: Portador Service MUST expose an aggregated endpoint returning cardholder + card + product.
- **FR-008**: Cartao Service MUST consume issuance messages from SQS and create a card.
- **FR-009**: Cartao Service MUST obtain product details from the Produto Service when creating or reading a card.
- **FR-010**: Cartao Service MUST cache product details in Redis to avoid excessive inter-service calls.
- **FR-011**: Cartao Service MUST support card status `ATIVO`, `BLOQUEADO`, `CANCELADO`.
- **FR-012**: The system MUST NOT create a card for a non-existent or cancelled product.
- **FR-013**: SQS consumption MUST apply a retry strategy and route exhausted messages to a Dead Letter Queue.
- **FR-014**: All services MUST return semantic HTTP status codes and a single, consistent error body.
- **FR-015**: All services MUST expose an OpenAPI/Swagger UI.
- **FR-016**: The whole environment MUST start with a single `docker compose up` command.
- **FR-017**: Card creation MUST be idempotent with respect to redelivered issuance messages.
- **FR-018**: Cartao Service MUST remain available for product reads when Redis is unavailable by falling back to the Produto Service.

### Key Entities

- **Product**: A card product type (Black, Gold, Platinum). Attributes: id, name, status, createdAt, updatedAt.
- **Cardholder (Portador)**: The client. Attributes: id, name, CPF, birth date, status, productId, createdAt, updatedAt.
- **Card (Cartao)**: The issued card. Attributes: id, cardholderId, productId, masked number, status, createdAt, updatedAt.
- **IssuanceMessage**: The SQS payload that triggers issuance. Attributes: cardholderId, productId, correlationId.
- **ProductSnapshot**: The product projection cached in Redis and embedded in aggregated responses.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A single `docker compose up` brings the full environment (Postgres, Redis, LocalStack, 3 services) to a healthy state with no manual steps.
- **SC-002**: End-to-end issuance (register cardholder -> card issued -> visible in aggregated query) completes within 10 seconds under normal conditions.
- **SC-003**: Repeated reads of the same product hit Redis on every call after the first within the TTL window (>=1 origin call, subsequent calls served from cache).
- **SC-004**: With the Produto Service offline, zero orphan cards are created and all unconfirmed issuance messages land in the DLQ.
- **SC-005**: With Redis offline, product reads still succeed via direct Produto Service calls (no `500`).
- **SC-006**: Critical-path flows (cardholder registration, queue consumption) are covered by automated unit and Testcontainers-backed integration tests, all green.

## Assumptions

- A single shared signing secret configures JWT across the environment for the challenge; a user-registration/login endpoint issues tokens (no external IdP).
- The card number is generated and stored masked; no real PAN/PCI handling is in scope.
- Each service owns its own PostgreSQL database (database-per-service) within one Postgres instance for simplicity.
- The aggregated view calls the Cartao Service synchronously to fetch the issued card; eventual consistency means the card may not exist immediately after registration.
- LocalStack provides SQS; queue and DLQ are provisioned automatically at environment startup.
- Audit, retry, and cache parameters (TTL, max retries, backoff) are externalized as configuration with sensible financial-grade defaults.
