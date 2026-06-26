# Card Process Constitution

## Core Principles

### I. Resilience First (NON-NEGOTIABLE)

The platform processes financial card lifecycles and must not stop. Every cross-service
or cross-infrastructure call MUST assume the dependency can fail. Synchronous integrations
are wrapped with timeouts, retries with exponential backoff, and circuit breakers.
Asynchronous integrations MUST provide at-least-once delivery, bounded retry, and a Dead
Letter Queue. A degraded dependency MUST surface as a semantic HTTP status or a retriable
message, never as an unhandled `500`.

### II. Data Integrity Over Availability for Writes

A card MUST NOT be created for a non-existent or cancelled product. Issuance validates the
product against the source of truth (Produto Service) before persisting a card. When the
product cannot be confirmed, the message is retried and ultimately dead-lettered rather than
producing an orphan card. Cache MAY serve reads; it MUST NOT be the authority that authorizes
a write against a missing product.

### III. Clean Architecture & SOLID

Each service is layered: `web` (controllers/DTOs) -> `application` (use cases/services) ->
`domain` (entities/enums/business rules) -> `infrastructure` (persistence, messaging, clients,
cache). Dependencies point inward. Domain holds no framework annotations beyond persistence
mapping. Single Responsibility per class; behavior is injected through interfaces so that
infrastructure is swappable and testable.

### IV. Explicit Contracts

Every service exposes an OpenAPI/Swagger contract. Inter-service payloads (REST bodies and
SQS messages) are versioned DTOs, never leaked domain entities. Errors follow a single
problem-detail shape across all services so clients parse one format.

### V. Test the Critical Paths

Registration (Portador issuance trigger) and queue consumption (Cartao card generation) are
the critical flows and MUST have automated coverage. Unit tests cover business rules in
isolation with mocks. Integration tests exercise real Postgres, Redis, and SQS through
Testcontainers so behavior is validated against actual infrastructure, not mocks.

## Engineering Standards

- **Language/Runtime**: Java 21, Spring Boot 3.x.
- **Persistence**: PostgreSQL per service (database-per-service); JPA auditing for created/updated timestamps.
- **Cache**: Redis, used by Cartao Service to cache product details with a bounded TTL.
- **Messaging**: AWS SQS via LocalStack; main queue + DLQ with a redrive policy.
- **Security**: Portador Service authenticates via JWT (stateless, signed, expiring tokens).
- **Code style**: No code comments - names and structure must carry intent. All identifiers,
  packages, and messages in English. Favor immutability, records for DTOs, and constructor injection.
- **Observability**: Structured logging at integration boundaries (message received, product
  cache hit/miss, DLQ routing, downstream failures).

## Delivery Standards

- The entire environment (Postgres, Redis, LocalStack, the 3 services) MUST start with a single
  `docker compose up` command, with health checks gating service start order.
- Each service ships a multi-stage Dockerfile building from source, so no host toolchain is required.
- The repository root provides `docker-compose.yml`, a professional `README.md` (setup, Mermaid
  architecture, technical decisions, integrity guarantees), and an API collection for manual testing.

## Governance

This constitution supersedes ad-hoc preferences. Any deviation MUST be recorded in the plan's
Complexity Tracking with its justification and the simpler rejected alternative. Resilience and
data-integrity principles are non-negotiable and cannot be traded for delivery speed. All design
artifacts (spec, plan, tasks) are checked against these principles before implementation.

**Version**: 1.0.0 | **Ratified**: 2026-06-26 | **Last Amended**: 2026-06-26
