# Phase 0 Research: Technical Decisions

Each decision lists the choice, the rationale, and the rejected alternatives. Decisions are bound by
the constitution (resilience first, integrity over availability for writes, clean architecture).

## D1. SQS integration (LocalStack)

**Decision**: Use Spring Cloud AWS 3.x (`spring-cloud-aws-starter-sqs`) for both the Portador
producer (`SqsTemplate`) and the Cartao consumer (`@SqsListener`), pointed at LocalStack via a custom
endpoint override.

**Rationale**: `@SqsListener` gives declarative consumption, message acknowledgement, batching and
back-pressure with minimal boilerplate, and integrates with Spring's lifecycle. It runs unchanged
against LocalStack and real AWS by only swapping the endpoint and credentials provider.

**Alternatives rejected**: Raw AWS SDK v2 polling loop (more boilerplate, manual ack/visibility
handling); JMS over SQS (extra abstraction, weaker control over redrive semantics).

## D2. Retry strategy and Dead Letter Queue

**Decision**: Rely on SQS-native redelivery. The main queue `card-issuance-queue` has a redrive
policy targeting `card-issuance-dlq` with `maxReceiveCount = 5`. The consumer does NOT swallow
exceptions: when product confirmation fails or is unconfirmed, it throws, the message is not
acknowledged, SQS makes it visible again after the visibility timeout, and after 5 receives SQS moves
it to the DLQ automatically.

**Rationale**: Broker-side redrive is the financial-grade standard - it survives consumer restarts
and crashes, needs no application state, and guarantees the DLQ captures poison messages. The
visibility timeout provides the backoff window between attempts.

**Alternatives rejected**: In-memory Spring Retry only (lost on crash, no durable DLQ); manually
re-publishing to a retry queue (reinvents what SQS provides natively).

## D3. Guaranteeing no card for a non-existent product

**Decision**: On consuming an issuance message, the Cartao Service calls the Produto Service
**source of truth** (`GET /products/{id}`) and requires status `ATIVO` before persisting a card. A
missing product yields `404` -> a domain `ProductNotFoundException`; a non-`ATIVO` product yields a
`ProductNotActiveException`. Both are treated as terminal-but-dead-letterable: no card is written and
the message is rejected so SQS redrives it and ultimately dead-letters it.

**Rationale**: Integrity over availability for writes (Principle II). The cache is a read accelerator,
not an authority; authorizing a write off a possibly stale cache could create orphan cards. Validating
against the authority before the write closes that gap.

**Distinction**: Reads (enrichment/aggregation) may use the cache; the write authorization path
always confirms against the origin.

**Alternatives rejected**: Trusting the Redis snapshot for write authorization (stale-data risk);
foreign-key across services (impossible - separate databases).

## D4. Redis cache strategy

**Decision**: Read-through cache with TTL. `ProductGateway` checks Redis first (`product:{id}`); on
miss it calls the Produto Service and stores the `ProductSnapshot` with a bounded TTL (default 10
minutes). Implemented with Spring Data Redis (`RedisTemplate` + JSON serializer) behind a
`ProductCache` interface.

**Rationale**: Read-through is simple, keeps the cache transparent to callers, and bounds staleness
with TTL. An explicit interface lets the gateway fall back to direct calls when Redis is unavailable
(Principle I) - the cache is best-effort for reads.

**Alternatives rejected**: Spring Cache abstraction `@Cacheable` (harder to express graceful Redis-down
fallback and cache-miss-with-circuit-breaker composition); write-through/cache-aside with manual
invalidation (no update path for products in challenge scope, TTL suffices); caching inside the
Produto Service (challenge mandates the Cartao Service owns the product cache).

## D5. Resilience of the Cartao -> Produto synchronous call

**Decision**: Wrap `ProdutoServiceClient` with Resilience4j: connect/read timeouts, retry with
exponential backoff for transient errors, and a circuit breaker. Redis-down and Produto-down are
handled distinctly - a Redis failure falls back to the direct (resilient) client call; a Produto
failure on a read surfaces as `503 Service Unavailable` (semantic, retriable) rather than `500`,
while on the write path it prevents card creation and lets the message redrive.

**Rationale**: Directly answers the brief's prompt ("how does the service behave if SQS falls or the
Produto Service is offline"). Timeouts stop thread exhaustion; backoff smooths transient blips; the
circuit breaker sheds load from a failing dependency.

**Alternatives rejected**: No resilience wrapper (cascading failures, `500`s); Spring Retry only (no
circuit breaking); Spring Cloud Circuit Breaker facade (extra layer over Resilience4j without benefit
here).

## D6. Idempotent consumption

**Decision**: Each issuance message carries a `correlationId`; the card table has a unique constraint
on `correlation_id` (and on `cardholder_id`). The consumer checks for an existing card before
creating and relies on the unique constraint as the final guard, treating a duplicate as a successful
no-op (ack) so redelivery never creates a second card.

**Rationale**: SQS is at-least-once; redelivery is expected. Idempotency makes reprocessing safe and
prevents duplicate cards (Principle II), without distributed locks.

**Alternatives rejected**: Exactly-once delivery assumption (SQS does not provide it for standard
queues); distributed lock (unnecessary given a DB unique constraint).

## D7. JWT authentication (Portador)

**Decision**: Stateless JWT with Spring Security. A `/auth/register` + `/auth/login` pair issues
HS256 tokens signed with a shared secret (env-configured); a `OncePerRequestFilter` validates the
bearer token and populates the security context. Cardholder endpoints require authentication.

**Rationale**: Stateless tokens fit microservices (no shared session store), are simple to verify, and
satisfy the explicit JWT requirement. jjwt is a small, focused library.

**Alternatives rejected**: Opaque tokens + introspection (needs a central auth store, more calls);
OAuth2 resource server with an external IdP (out of challenge scope, heavier infra).

## D8. Persistence, auditing and migrations

**Decision**: PostgreSQL with Spring Data JPA; database-per-service. Auditing via JPA
`@CreatedDate`/`@LastModifiedDate` (`AuditingEntityListener`) for `createdAt`/`updatedAt`. Schema
managed by Flyway so each service owns and versions its schema deterministically.

**Rationale**: JPA auditing satisfies the "simple audit" senior requirement declaratively. Flyway
gives reproducible schemas across fresh compose runs (no `ddl-auto=update` surprises) - important for
financial reproducibility.

**Alternatives rejected**: Hibernate `ddl-auto=update` (non-deterministic, unsafe for finance);
Liquibase (equivalent; Flyway's SQL-first style is leaner here); manual timestamp handling (boilerplate
the auditing listener removes).

## D9. Build and runtime topology

**Decision**: Maven multi-module monorepo with an aggregator parent POM and a `shared-contracts`
module. Each service has a multi-stage Dockerfile (Maven build stage -> slim JRE 21 runtime stage).
`docker-compose.yml` wires Postgres, Redis, LocalStack and the three services with health checks and
`depends_on: condition: service_healthy` for correct ordering.

**Rationale**: One `mvn` build for the whole repo; reproducible container builds with no host
toolchain; health-gated startup avoids race conditions (services wait for Postgres/Redis/SQS to be
ready). Satisfies the single-command boot requirement.

**Alternatives rejected**: Polyrepo (heavier to review/run for a challenge); pre-built jars copied in
(non-reproducible); no health checks (flaky startup ordering).

## D10. Documentation and testing tooling

**Decision**: springdoc-openapi exposes Swagger UI per service. Testcontainers (Postgres, Redis,
LocalStack) backs integration tests for the two critical flows; Awaitility asserts async issuance.
A Postman collection covers the manual end-to-end path.

**Rationale**: Testcontainers validates behavior against real infrastructure (a stated differentiator)
rather than mocks; Awaitility expresses eventual consistency cleanly; the collection makes manual
evaluation one-click.

**Alternatives rejected**: Embedded H2 + mocked SQS (does not exercise real integration behavior);
WireMock-only (does not test the broker/cache/db wiring end to end).
