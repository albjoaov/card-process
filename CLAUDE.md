# Card Process

Multi-module Maven monorepo: three Spring Boot 3.3 / Java 21 microservices that issue cards
asynchronously via AWS SQS (LocalStack in dev). Built spec-driven; the spec/plan/tasks artifacts
live in `specs/001-card-processing-ecosystem/`.

## Commands

Use the Maven wrapper (`./mvnw`, pinned to 3.9.9) — no local Maven install required.

- `./mvnw test` — all tests. Docker must be running (Testcontainers: Postgres, Redis, LocalStack, WireMock).
- `./mvnw -pl cartao-service test -Dtest=CardConsumptionIntegrationTest` — single module / single test.
- `./mvnw clean install -DskipTests` — build all modules.
- `docker compose up --build` — full stack (Postgres, Redis, LocalStack + the 3 services), single command.
- Local dev loop (services on the JVM, infra in Docker): `docker compose up postgres redis localstack -d`,
  then `./mvnw -pl <service> spring-boot:run` per service — see `docs/guias/local-dev-setup.md`.

## Architecture

| Module | Port | Role |
|---|---|---|
| `produto-service` | 8081 | Product catalog — **source of truth** for products (`produto_db`) |
| `portador-service` | 8082 | Cardholder registration, JWT auth, outbox publisher, aggregated view (`portador_db`) |
| `cartao-service` | 8083 | SQS consumer, card issuance, Redis read-through product cache (`cartao_db`) |
| `shared-contracts` | — | The `IssuanceMessage` wire contract shared by producer and consumer |

Each service is layered `web -> application -> domain -> infrastructure`, dependencies pointing
inward. Database-per-service; schemas are managed by Flyway (never edit an applied migration — add
a new one). Errors are RFC 7807 `ProblemDetail` via global handlers.

## Invariants — do not break these

- **No orphan cards**: card creation validates the product against the Produto Service (source of
  truth), never the cache. On failure the listener throws, the SQS message is not acked, and after
  5 receives it goes to the DLQ. The cache is a read accelerator only: fail-closed writes,
  fail-open reads.
- **Transactional outbox**: `portador-service` never publishes to SQS directly on registration —
  the outbox row commits in the same transaction as the cardholder; `OutboxRelay` delivers
  at-least-once and the consumer's idempotency (unique `correlation_id`/`cardholder_id`) absorbs
  duplicates. Do not "simplify" back to a direct publish.
- **`SqsIssuanceSender` uses `SqsAsyncClient` directly** with a success-only queue-URL cache,
  because `SqsTemplate` caches a *failed* queue resolution forever. Do not swap it back.

## Gotchas

- Surefire pins the Docker API version (`api.version=1.41`) so Testcontainers works on OrbStack —
  don't remove the `argLine`.
- `application.yml` defaults every env var to localhost values, so local runs need zero config;
  `docker-compose.yml` overrides for the container network. Keep both in sync when adding config.
- Prod compose (`docker-compose.prod.yml`) swaps LocalStack for ElasticMQ and requires
  `POSTGRES_PASSWORD`/`JWT_SECRET` (see `.env.production.example`).

## Docs

`docs/README.md` is the index: guides (`docs/guias/`), build reports (`docs/relatorios/`).
